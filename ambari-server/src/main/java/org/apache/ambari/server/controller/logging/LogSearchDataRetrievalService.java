/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ambari.server.controller.logging;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.ambari.server.AmbariService;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.controller.AmbariServer;
import org.apache.commons.collections.MapUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.AbstractService;
import com.google.inject.Inject;
import com.google.inject.Injector;


/**
 * The {@link LogSearchDataRetrievalService} is an Ambari Service that
 *   is used by the Ambari LogSearch integration code to obtain response
 *   data from the LogSearch server.
 *
 * In order to improve the performance of the LogSearch integration layer in
 *   Ambari, this service implements the following:
 *
 *  <ul>
 *    <li>A cache for LogSearch data that typically is returned by the LogSearch REST API</li>
 *    <li>Implements the remote request for LogSearch data not found in the cache on a separate
 *        thread, which keeps the request from affecting the overall performance of the
 *        Ambari REST API</li>
 *  </ul>
 *
 *  As with other services annotated with {@link AmbariService}, this class may be
 *    injected in order to obtain cached access to the LogSearch responses.
 *
 *  Caches are initially empty in this implementation, and a remote request
 *    to the LogSearch server will be made upon the first request for a given
 *    response.
 *
 *
 */
@AmbariService
public class LogSearchDataRetrievalService extends AbstractService {

  private static final Logger LOG = LoggerFactory.getLogger(LogSearchDataRetrievalService.class);

  /**
   * Maximum number of failed attempts that the LogSearch integration code will attempt for
   *   a given component before treating the component as failed and skipping the request.
   *
   */
  private static int MAX_RETRIES_FOR_FAILED_METADATA_REQUEST = 10;

  /**
   * Factory instance used to handle URL string generation requests on the
   *   main request thread.
   */
  @Inject
  private LoggingRequestHelperFactory loggingRequestHelperFactory;

  @Inject
  private Injector injector;

  @Inject
  private Configuration ambariServerConfiguration;

  /**
   * A Cache of host+component names to a set of log files associated with
   *  that Host/Component combination.  This data is retrieved from the
   *  LogSearch server, but cached here for better performance.
   */
  private Cache<String, Set<String>> logFileNameCache;

  /**
   * A Cache of host+component names to a generated URI that
   *  can be used to access the "tail" of a given log file.
   *
   * This data is generated by ambari-server, but cached here to
   *  avoid re-creating these strings upon multiple calls to the
   *  associated HostComponent resource.
   */
  private Cache<String, String> logFileTailURICache;

  /**
   * A set that maintains the current requests being made,
   *  but not yet completed.  This Set will be used to
   *  keep multiple requests from being made for the same
   *  host/component combination.
   *
   */
  private final Set<String> currentRequests = Sets.newConcurrentHashSet();

  /**
   * A map that maintains the set of failure counts for logging
   * metadata requests on a per-component basis.  This map should
   * be consulted prior to making a metadata request to the LogSearch
   * service.  If LogSearch has already returned an empty list for the given
   * component, or any other error has occurred for a certain number of attempts,
   * the request should not be attempted further.
   *
   */
  private final Map<String, AtomicInteger> componentRequestFailureCounts =
    Maps.newConcurrentMap();



  /**
   * Executor instance to be used to run REST queries against
   * the LogSearch service.
   */
  private Executor executor;

  @Override
  protected void doStart() {

    LOG.debug("Initializing caches");

    // obtain the max cache expire time from the ambari configuration
    final int maxTimeoutForCacheInHours =
      ambariServerConfiguration.getLogSearchMetadataCacheExpireTimeout();

    LOG.debug("Caches configured with a max expire timeout of {} hours.", maxTimeoutForCacheInHours);

    // initialize the log file name cache
    logFileNameCache = CacheBuilder.newBuilder().expireAfterWrite(maxTimeoutForCacheInHours, TimeUnit.HOURS).build();
    // initialize the log file tail URI cache
    logFileTailURICache = CacheBuilder.newBuilder().expireAfterWrite(maxTimeoutForCacheInHours, TimeUnit.HOURS).build();

    // initialize the Executor
    executor = Executors.newSingleThreadExecutor();
  }

  @Override
  protected void doStop() {
    LOG.debug("Invalidating LogSearch caches");
    // invalidate the caches
    logFileNameCache.invalidateAll();

    logFileTailURICache.invalidateAll();
  }

  /**
   * This method attempts to obtain the log file names for the specified component
   *   on the specified host.  A cache lookup is first attempted. If the cache does not contain
   *   this data, an asynchronous task is launched in order to make the REST request to
   *   the LogSearch server to obtain this data.
   *
   * Once the data is available in the cache, subsequent calls for a given Host/Component
   *   combination should return non-null.
   *
   * @param component the component name
   * @param host the host name
   * @param cluster the cluster name
   *
   * @return a Set<String> that includes the log file names associated with this Host/Component
   *         combination, or null if that object does not exist in the cache.
   */
  public Set<String> getLogFileNames(String component, String host, String cluster) {
    final String key = generateKey(component, host);

    // check cache for data
    Set<String> cacheResult = logFileNameCache.getIfPresent(key);

    if (cacheResult != null) {
      LOG.debug("LogFileNames result for key = {} found in cache", key);
      return cacheResult;
    } else {
      if (!componentRequestFailureCounts.containsKey(component) || componentRequestFailureCounts.get(component).get() < MAX_RETRIES_FOR_FAILED_METADATA_REQUEST) {
        // queue up a thread to create the LogSearch REST request to obtain this information
        if (currentRequests.contains(key)) {
          LOG.debug("LogFileNames request has been made for key = {}, but not completed yet", key);
        } else {
          LOG.debug("LogFileNames result for key = {} not in cache, queueing up remote request", key);
          // add request key to queue, to keep multiple copies of the same request from
          // being submitted
          currentRequests.add(key);
          startLogSearchFileNameRequest(host, component, cluster);
        }
      } else {
        LOG.debug("Too many failures occurred while attempting to obtain log file metadata for component = {}, Ambari will ignore this component for LogSearch Integration", component);
      }
    }

    return null;
  }

  public String getLogFileTailURI(String baseURI, String component, String host, String cluster) {
    String key = generateKey(component, host);

    String result = logFileTailURICache.getIfPresent(key);
    if (result != null) {
      // return cached result
      return result;
    } else {
      // create URI and add to cache before returning
      if (loggingRequestHelperFactory != null) {
        LoggingRequestHelper helper =
          loggingRequestHelperFactory.getHelper(getController(), cluster);

        if (helper != null) {
          String tailFileURI =
            helper.createLogFileTailURI(baseURI, component, host);

          if (tailFileURI != null) {
            logFileTailURICache.put(key, tailFileURI);
            return tailFileURI;
          }
        }
      } else {
        LOG.debug("LoggingRequestHelperFactory not set on the retrieval service, this probably indicates an error in setup of this service.");
      }
    }

    return null;
  }

  protected void setLoggingRequestHelperFactory(LoggingRequestHelperFactory loggingRequestHelperFactory) {
    this.loggingRequestHelperFactory = loggingRequestHelperFactory;
  }

  /**
   * Package-level setter to facilitate simpler unit testing
   *
   * @param injector
   */
  void setInjector(Injector injector) {
    this.injector = injector;
  }

  /**
   * This protected method provides a way for unit-tests to insert a
   * mock executor for simpler unit-testing.
   *
   * @param executor an Executor instance
   */
  protected void setExecutor(Executor executor) {
    this.executor = executor;
  }

  /**
   * Package-level setter to facilitate simpler unit testing
   *
   * @param ambariServerConfiguration
   */
  void setConfiguration(Configuration ambariServerConfiguration) {
    this.ambariServerConfiguration = ambariServerConfiguration;
  }

  /**
   * This protected method allows for simpler unit tests.
   *
   * @return the Set of current Requests that are not yet completed
   */
  protected Set<String> getCurrentRequests() {
    return currentRequests;
  }

  /**
   * This protected method allows for simpler unit tests.
   *
   * @return the Map of failure counts on a per-component basis
   */
  protected Map<String, AtomicInteger> getComponentRequestFailureCounts() {
    return componentRequestFailureCounts;
  }

  private void startLogSearchFileNameRequest(String host, String component, String cluster) {
    // Create a separate instance of LoggingRequestHelperFactory for
    // each task launched, since these tasks will occur on a separate thread
    // TODO: In a future patch, this should be refactored, to either remove the need
    // TODO: for the separate factory instance at the level of this class, or to make
    // TODO: the LoggingRequestHelperFactory implementation thread-safe, so that
    // TODO: a single factory instance can be shared across multiple threads safely
    executor.execute(new LogSearchFileNameRequestRunnable(host, component, cluster, logFileNameCache, currentRequests,
                                                          injector.getInstance(LoggingRequestHelperFactory.class), componentRequestFailureCounts));
  }

  private AmbariManagementController getController() {
    return AmbariServer.getController();
  }



  private static String generateKey(String component, String host) {
    return component + "+" + host;
  }


  /**
   * A {@link Runnable} used to make requests to the remote LogSearch server's
   *   REST API.
   *
   * This implementation will update a cache shared with the {@link LogSearchDataRetrievalService},
   *   which can then be used for subsequent requests for the same data.
   *
   */
  static class LogSearchFileNameRequestRunnable implements Runnable {

    private final String host;

    private final String component;

    private final String cluster;

    private final Set<String> currentRequests;

    private final Cache<String, Set<String>> logFileNameCache;

    private LoggingRequestHelperFactory loggingRequestHelperFactory;

    private final Map<String, AtomicInteger> componentRequestFailureCounts;

    private AmbariManagementController controller;

    LogSearchFileNameRequestRunnable(String host, String component, String cluster, Cache<String, Set<String>> logFileNameCache, Set<String> currentRequests, LoggingRequestHelperFactory loggingRequestHelperFactory,
                                     Map<String, AtomicInteger> componentRequestFailureCounts) {
      this(host, component, cluster, logFileNameCache, currentRequests, loggingRequestHelperFactory, componentRequestFailureCounts, AmbariServer.getController());
    }

    LogSearchFileNameRequestRunnable(String host, String component, String cluster, Cache<String, Set<String>> logFileNameCache, Set<String> currentRequests,
                                               LoggingRequestHelperFactory loggingRequestHelperFactory, Map<String, AtomicInteger> componentRequestFailureCounts, AmbariManagementController controller) {
      this.host  = host;
      this.component = component;
      this.cluster = cluster;
      this.logFileNameCache = logFileNameCache;
      this.currentRequests = currentRequests;
      this.loggingRequestHelperFactory = loggingRequestHelperFactory;
      this.componentRequestFailureCounts = componentRequestFailureCounts;
      this.controller = controller;
    }

    @Override
    public void run() {
      LOG.debug("LogSearchFileNameRequestRunnable: starting...");
      try {
        LoggingRequestHelper helper =
          loggingRequestHelperFactory.getHelper(controller, cluster);

        if (helper != null) {
          // make request to LogSearch service
          HostLogFilesResponse logFilesResponse = helper.sendGetLogFileNamesRequest(host);
          // update the cache if result is available
          if (logFilesResponse != null && MapUtils.isNotEmpty(logFilesResponse.getHostLogFiles())) {
            LOG.debug("LogSearchFileNameRequestRunnable: request was successful, updating cache");
            // update cache with returned result
            for (Map.Entry<String, List<String>> componentEntry : logFilesResponse.getHostLogFiles().entrySet()) {
              final String key = generateKey(componentEntry.getKey(), host);
              logFileNameCache.put(key, new HashSet<>(componentEntry.getValue()));
            }
          } else {
            LOG.debug("LogSearchFileNameRequestRunnable: remote request was not successful for component = {} on host ={}", component, host);
            if (!componentRequestFailureCounts.containsKey(component)) {
              componentRequestFailureCounts.put(component, new AtomicInteger());
            }

            // increment the failure count for this component
            componentRequestFailureCounts.get(component).incrementAndGet();
          }
        } else {
          LOG.debug("LogSearchFileNameRequestRunnable: request helper was null.  This may mean that LogSearch is not available, or could be a potential connection problem.");
        }
      } finally {
        // since request has completed (either successfully or not),
        // remove this host/component key from the current requests
        currentRequests.remove(generateKey(component, host));
      }
    }

    protected void setLoggingRequestHelperFactory(LoggingRequestHelperFactory loggingRequestHelperFactory) {
      this.loggingRequestHelperFactory = loggingRequestHelperFactory;
    }

    protected void setAmbariManagementController(AmbariManagementController controller) {
      this.controller = controller;
    }


  }


}
