![ambari-lite pre-commit build](https://github.com/powerLambda/ambari-lite/workflows/ambari-lite%20pre-commit%20build/badge.svg?branch=master)


# Project Backound
ambari-lite is forked from tags/release-2.7.4, the project aim is to:  
1,maintain core features to support classic hadoop cluster deployment and management;  
2,automate repo build and function test to guarantee quality assurance of this fork release;  

## Features Prune and Improvement  
1, Remove ambari-metrics module([AMBARI-24695] Remove ambari-metrics from ambari repository. #2388);   
2, Remove ambari-ambari-infra and ambari-logsearch module([AMBARI-24693]. Remove ambari-infra and ambari-logsearch from ambari repository. #2387)   
3, Remove timeline components in HDP stack as it is not widely supported by hadoop community;   


# How to build
Building Ambari   
	mvn clean package rpm:rpm -DskipTests -Drat.skip -Dpython.ver="python >= 2.6"   

## Local Build Issues
1,Failure to find org.apache.phoenix:phoenix-core:jar:5.0.0.3.1.4.0-315 in https://repo.maven.apache.org/maven2 was cached in the local repository, resolution will not be reattempted until the update interval of central maven repo has elapsed or updates are forced.  
Use -U flag to force maven update.  


#How to integrate apache distributed components?   
Apache Spark   





