<!--
{% comment %}
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements.  See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to you under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
{% endcomment %}
-->
Apache Hive (TM)
================
[![Master Build Status](https://travis-ci.org/apache/hive.svg?branch=master)](https://travis-ci.org/apache/hive/branches)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.apache.hive/hive/badge.svg)](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22org.apache.hive%22)

The Apache Hive (TM) data warehouse software facilitates reading,
writing, and managing large datasets residing in distributed storage
using SQL. Built on top of Apache Hadoop (TM), it provides:

* Tools to enable easy access to data via SQL, thus enabling data
  warehousing tasks such as extract/transform/load (ETL), reporting,
  and data analysis

* A mechanism to impose structure on a variety of data formats

* Access to files stored either directly in Apache HDFS (TM) or in other
  data storage systems such as Apache HBase (TM)

* Query execution using Apache Tez framework, designed for interactive query, 
  and has substantially reduced overheads versus MapReduce.

Hive provides standard SQL functionality, including many of the later
2003 and 2011 features for analytics.  These include OLAP functions,
subqueries, common table expressions, and more.  Hive's SQL can also be
extended with user code via user defined functions (UDFs), user defined
aggregates (UDAFs), and user defined table functions (UDTFs).

Hive is not designed for online transaction processing. It is best used
for traditional data warehousing tasks where the amount of data processed 
is large enough to require a distributed system. Hive is designed to maximize
scalability (scale out with more machines added dynamically to the Hadoop
cluster), performance, extensibility, fault-tolerance, and
loose-coupling with its input formats.


General Info
============

For the latest information about Hive, please visit out website at:

  http://hive.apache.org/


Getting Started
===============

- Installation Instructions and a quick tutorial:
  https://cwiki.apache.org/confluence/display/Hive/GettingStarted
  https://hive.apache.org/development/quickstart/

- Instructions to build Hive from source:
  https://cwiki.apache.org/confluence/display/Hive/GettingStarted#GettingStarted-BuildingHivefromSource

- A longer tutorial that covers more features of HiveQL:
  https://cwiki.apache.org/confluence/display/Hive/Tutorial

- The HiveQL Language Manual:
  https://cwiki.apache.org/confluence/display/Hive/LanguageManual


Requirements
============

Java
------

| Hive Version  | Java Version  |
| ------------- |:-------------:|
| Hive 4.0.1      | Java 8        |
| Hive 4.1.x      | Java 17        |
| Hive 4.2.x      | Java 21        |


Hadoop
------

- Hadoop 3.x


Upgrading from older versions of Hive
=====================================

- Hive includes changes to the MetaStore schema. If
  you are upgrading from an earlier version of Hive it is imperative
  that you upgrade the MetaStore schema by running the appropriate
  schema upgrade scripts located in the scripts/metastore/upgrade
  directory.

- We have provided upgrade scripts for MySQL, PostgreSQL, Oracle,
  Microsoft SQL Server, and Derby databases. If you are using a
  different database for your MetaStore you will need to provide
  your own upgrade script.

Useful mailing lists
====================

1. user@hive.apache.org - To discuss and ask usage questions. Send an
   empty email to user-subscribe@hive.apache.org in order to subscribe
   to this mailing list.

2. dev@hive.apache.org - For discussions about code, design and features.
   Send an empty email to dev-subscribe@hive.apache.org in order to
   subscribe to this mailing list.

3. commits@hive.apache.org - In order to monitor commits to the source
   repository. Send an empty email to commits-subscribe@hive.apache.org
   in order to subscribe to this mailing list.
