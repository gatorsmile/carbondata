/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.carbondata.spark.testsuite.dataload

import java.io.{File, FilenameFilter}

import org.apache.carbondata.core.constants.CarbonCommonConstants
import org.apache.carbondata.core.util.CarbonProperties
import org.apache.carbondata.spark.exception.MalformedCarbonCommandException
import org.apache.spark.sql.Row
import org.apache.spark.sql.common.util.QueryTest
import org.apache.spark.sql.test.TestQueryExecutor.projectPath
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

class TestGlobalSortDataLoad extends QueryTest with BeforeAndAfterEach with BeforeAndAfterAll {
  var filePath: String = s"$resourcesPath/globalsort"

  override def beforeEach {
    resetConf()

    sql("DROP TABLE IF EXISTS carbon_globalsort")
    sql(
      """
        | CREATE TABLE carbon_globalsort(id INT, name STRING, city STRING, age INT)
        | STORED BY 'org.apache.carbondata.format'
      """.stripMargin)
  }

  override def afterEach {
    resetConf()

    sql("DROP TABLE IF EXISTS carbon_globalsort")
  }

  override def beforeAll {
    sql("DROP TABLE IF EXISTS carbon_localsort_once")
    sql(
      """
        | CREATE TABLE carbon_localsort_once(id INT, name STRING, city STRING, age INT)
        | STORED BY 'org.apache.carbondata.format'
      """.stripMargin)
    sql(s"LOAD DATA LOCAL INPATH '$filePath' INTO TABLE carbon_localsort_once")
  }

  override def afterAll {
    sql("DROP TABLE IF EXISTS carbon_localsort_once")
    sql("DROP TABLE IF EXISTS carbon_localsort_twice")
    sql("DROP TABLE IF EXISTS carbon_localsort_triple")
    sql("DROP TABLE IF EXISTS carbon_localsort_delete")
    sql("DROP TABLE IF EXISTS carbon_localsort_update")
    sql("DROP TABLE IF EXISTS carbon_localsort_difftypes")
    sql("DROP TABLE IF EXISTS carbon_globalsort")
    sql("DROP TABLE IF EXISTS carbon_globalsort_partitioned")
    sql("DROP TABLE IF EXISTS carbon_globalsort_difftypes")
  }

  // ----------------------------------- Compare Result -----------------------------------
  test("Make sure the result is right and sorted in global level") {
    sql(s"LOAD DATA LOCAL INPATH '$filePath' INTO TABLE carbon_globalsort " +
      "OPTIONS('SORT_SCOPE'='GLOBAL_SORT', 'GLOBAL_SORT_PARTITIONS'='1')")

    assert(getIndexFileCount("carbon_globalsort") === 1)
    checkAnswer(sql("SELECT COUNT(*) FROM carbon_globalsort"), Seq(Row(12)))
    checkAnswer(sql("SELECT * FROM carbon_globalsort"),
      sql("SELECT * FROM carbon_localsort_once ORDER BY name"))
  }

  // ----------------------------------- Bad Record -----------------------------------
  test("Test GLOBAL_SORT with BAD_RECORDS_ACTION = 'FAIL'") {
    intercept[Exception] {
      sql(s"LOAD DATA LOCAL INPATH '$filePath' INTO TABLE carbon_globalsort " +
        "OPTIONS('SORT_SCOPE'='GLOBAL_SORT', 'BAD_RECORDS_ACTION'='FAIL')")
    }
  }

  test("Test GLOBAL_SORT with BAD_RECORDS_ACTION = 'REDIRECT'") {
    sql(s"LOAD DATA LOCAL INPATH '$filePath' INTO TABLE carbon_globalsort " +
      "OPTIONS('SORT_SCOPE'='GLOBAL_SORT', 'BAD_RECORDS_ACTION'='REDIRECT')")

    assert(getIndexFileCount("carbon_globalsort") === 3)
    checkAnswer(sql("SELECT COUNT(*) FROM carbon_globalsort"), Seq(Row(11)))
  }

  // ----------------------------------- Single Pass -----------------------------------
  // Waiting for merge [CARBONDATA-1145]
  ignore("Test GLOBAL_SORT with SINGLE_PASS") {
    sql(s"LOAD DATA LOCAL INPATH '$filePath' INTO TABLE carbon_globalsort " +
      "OPTIONS('SORT_SCOPE'='GLOBAL_SORT', 'SINGLE_PASS'='TRUE')")

    assert(getIndexFileCount("carbon_globalsort") === 3)
    checkAnswer(sql("SELECT COUNT(*) FROM carbon_globalsort"), Seq(Row(12)))
    checkAnswer(sql("SELECT * FROM carbon_globalsort ORDER BY name"),
      sql("SELECT * FROM carbon_localsort_once ORDER BY name"))
  }

  // ----------------------------------- Configuration Validity -----------------------------------
  test("Don't support GLOBAL_SORT on partitioned table") {
    sql("DROP TABLE IF EXISTS carbon_globalsort_partitioned")
    sql(
      """
        | CREATE TABLE carbon_globalsort_partitioned(name STRING, city STRING, age INT)
        | PARTITIONED BY (id INT)
        | STORED BY 'org.apache.carbondata.format'
        | TBLPROPERTIES('PARTITION_TYPE'='HASH','NUM_PARTITIONS'='3')
      """.stripMargin)

    intercept[MalformedCarbonCommandException] {
      sql(s"LOAD DATA LOCAL INPATH '$filePath' INTO TABLE carbon_globalsort_partitioned " +
        "OPTIONS('SORT_SCOPE'='GLOBAL_SORT')")
    }
  }

  test("Number of partitions should be greater than 0") {
    intercept[MalformedCarbonCommandException] {
      sql(s"LOAD DATA LOCAL INPATH '$filePath' INTO TABLE carbon_globalsort " +
        "OPTIONS('SORT_SCOPE'='GLOBAL_SORT', 'GLOBAL_SORT_PARTITIONS'='0')")
    }

    intercept[MalformedCarbonCommandException] {
      sql(s"LOAD DATA LOCAL INPATH '$filePath' INTO TABLE carbon_globalsort " +
        "OPTIONS('SORT_SCOPE'='GLOBAL_SORT', 'GLOBAL_SORT_PARTITIONS'='a')")
    }
  }

  // ----------------------------------- Compaction -----------------------------------
  test("Compaction GLOBAL_SORT * 2") {
    sql("DROP TABLE IF EXISTS carbon_localsort_twice")
    sql(
      """
        | CREATE TABLE carbon_localsort_twice(id INT, name STRING, city STRING, age INT)
        | STORED BY 'org.apache.carbondata.format'
      """.stripMargin)
    sql(s"LOAD DATA LOCAL INPATH '$filePath' INTO TABLE carbon_localsort_twice")
    sql(s"LOAD DATA LOCAL INPATH '$filePath' INTO TABLE carbon_localsort_twice")

    sql(s"LOAD DATA LOCAL INPATH '$filePath' INTO TABLE carbon_globalsort " +
      s"OPTIONS('SORT_SCOPE'='GLOBAL_SORT')")
    sql(s"LOAD DATA LOCAL INPATH '$filePath' INTO TABLE carbon_globalsort " +
      s"OPTIONS('SORT_SCOPE'='GLOBAL_SORT')")
    sql("ALTER TABLE carbon_globalsort COMPACT 'MAJOR'")

    assert(getIndexFileCount("carbon_globalsort") === 3)
    checkAnswer(sql("SELECT COUNT(*) FROM carbon_globalsort"), Seq(Row(24)))
    checkAnswer(sql("SELECT * FROM carbon_globalsort ORDER BY name"),
      sql("SELECT * FROM carbon_localsort_twice ORDER BY name"))
  }

  test("Compaction GLOBAL_SORT + LOCAL_SORT + BATCH_SORT") {
    sql("DROP TABLE IF EXISTS carbon_localsort_triple")
    sql(
      """
        | CREATE TABLE carbon_localsort_triple(id INT, name STRING, city STRING, age INT)
        | STORED BY 'org.apache.carbondata.format'
      """.stripMargin)
    sql(s"LOAD DATA LOCAL INPATH '$filePath' INTO TABLE carbon_localsort_triple")
    sql(s"LOAD DATA LOCAL INPATH '$filePath' INTO TABLE carbon_localsort_triple")
    sql(s"LOAD DATA LOCAL INPATH '$filePath' INTO TABLE carbon_localsort_triple")

    sql(s"LOAD DATA LOCAL INPATH '$filePath' INTO TABLE carbon_globalsort " +
      s"OPTIONS('SORT_SCOPE'='GLOBAL_SORT')")
    sql(s"LOAD DATA LOCAL INPATH '$filePath' INTO TABLE carbon_globalsort " +
      s"OPTIONS('SORT_SCOPE'='LOCAL_SORT')")
    sql(s"LOAD DATA LOCAL INPATH '$filePath' INTO TABLE carbon_globalsort " +
      s"OPTIONS('SORT_SCOPE'='BATCH_SORT', 'BATCH_SORT_SIZE_INMB'='1')")
    sql("ALTER TABLE carbon_globalsort COMPACT 'MAJOR'")

    assert(getIndexFileCount("carbon_globalsort") === 3)
    checkAnswer(sql("SELECT COUNT(*) FROM carbon_globalsort"), Seq(Row(36)))
    checkAnswer(sql("SELECT * FROM carbon_globalsort ORDER BY name"),
      sql("SELECT * FROM carbon_localsort_triple ORDER BY name"))
  }

  // ----------------------------------- Check Configurations -----------------------------------
  // Waiting for merge SET feature[CARBONDATA-1065]
  ignore("DDL > SET") {
    sql(s"SET ${CarbonCommonConstants.LOAD_SORT_SCOPE} = LOCAL_SORT")
    sql(s"SET ${CarbonCommonConstants.LOAD_GLOBAL_SORT_PARTITIONS} = 5")
    sql(s"LOAD DATA LOCAL INPATH '$filePath' INTO TABLE carbon_globalsort " +
      "OPTIONS('SORT_SCOPE'='GLOBAL_SORT', 'GLOBAL_SORT_PARTITIONS'='2')")

    assert(getIndexFileCount("carbon_globalsort") === 2)
  }

  test("DDL > carbon.properties") {
    CarbonProperties.getInstance().addProperty(CarbonCommonConstants.LOAD_SORT_SCOPE, "LOCAL_SORT")
    CarbonProperties.getInstance().addProperty(CarbonCommonConstants.LOAD_GLOBAL_SORT_PARTITIONS, "5")
    sql(s"LOAD DATA LOCAL INPATH '$filePath' INTO TABLE carbon_globalsort " +
      "OPTIONS('SORT_SCOPE'='GLOBAL_SORT', 'GLOBAL_SORT_PARTITIONS'='2')")

    assert(getIndexFileCount("carbon_globalsort") === 2)
  }

  // Waiting for merge SET feature[CARBONDATA-1065]
  ignore("SET > carbon.properties") {
    CarbonProperties.getInstance().addProperty(CarbonCommonConstants.LOAD_SORT_SCOPE, "LOCAL_SORT")
    CarbonProperties.getInstance().addProperty(CarbonCommonConstants.LOAD_GLOBAL_SORT_PARTITIONS, "5")
    sql(s"SET ${CarbonCommonConstants.LOAD_SORT_SCOPE} = GLOBAL_SORT")
    sql(s"SET ${CarbonCommonConstants.LOAD_GLOBAL_SORT_PARTITIONS} = 2")
    sql(s"LOAD DATA LOCAL INPATH '$filePath' INTO TABLE carbon_globalsort")

    assert(getIndexFileCount("carbon_globalsort") === 2)
  }

  test("carbon.properties") {
    CarbonProperties.getInstance().addProperty(CarbonCommonConstants.LOAD_SORT_SCOPE, "GLOBAL_SORT")
    CarbonProperties.getInstance().addProperty(CarbonCommonConstants.LOAD_GLOBAL_SORT_PARTITIONS, "2")
    sql(s"LOAD DATA LOCAL INPATH '$filePath' INTO TABLE carbon_globalsort")

    assert(getIndexFileCount("carbon_globalsort") === 2)
  }

  // ----------------------------------- IUD -----------------------------------
  test("LOAD with DELETE") {
    sql("DROP TABLE IF EXISTS carbon_localsort_delete")
    sql(
      """
        | CREATE TABLE carbon_localsort_delete(id INT, name STRING, city STRING, age INT)
        | STORED BY 'org.apache.carbondata.format'
      """.stripMargin)
    sql(s"LOAD DATA LOCAL INPATH '$filePath' INTO TABLE carbon_localsort_delete")
    sql("DELETE FROM carbon_localsort_delete WHERE id = 1").show

    sql(s"LOAD DATA LOCAL INPATH '$filePath' INTO TABLE carbon_globalsort " +
      "OPTIONS('SORT_SCOPE'='GLOBAL_SORT')")
    sql("DELETE FROM carbon_globalsort WHERE id = 1").show

    assert(getIndexFileCount("carbon_globalsort") === 3)
    checkAnswer(sql("SELECT COUNT(*) FROM carbon_globalsort"), Seq(Row(11)))
    checkAnswer(sql("SELECT * FROM carbon_globalsort ORDER BY name"),
      sql("SELECT * FROM carbon_localsort_delete ORDER BY name"))
  }

  test("LOAD with UPDATE") {
    sql("DROP TABLE IF EXISTS carbon_localsort_update")
    sql(
      """
        | CREATE TABLE carbon_localsort_update(id INT, name STRING, city STRING, age INT)
        | STORED BY 'org.apache.carbondata.format'
      """.stripMargin)
    sql(s"LOAD DATA LOCAL INPATH '$filePath' INTO TABLE carbon_localsort_update")
    sql("UPDATE carbon_localsort_update SET (name) = ('bb') WHERE id = 2").show

    sql(s"LOAD DATA LOCAL INPATH '$filePath' INTO TABLE carbon_globalsort " +
      "OPTIONS('SORT_SCOPE'='GLOBAL_SORT')")
    sql("UPDATE carbon_globalsort SET (name) = ('bb') WHERE id = 2").show

    checkAnswer(sql("SELECT COUNT(*) FROM carbon_globalsort"), Seq(Row(12)))
    checkAnswer(sql("SELECT name FROM carbon_globalsort WHERE id = 2"), Seq(Row("bb")))
    checkAnswer(sql("SELECT * FROM carbon_globalsort ORDER BY name"),
      sql("SELECT * FROM carbon_localsort_update ORDER BY name"))
  }

  // ----------------------------------- INSERT INTO -----------------------------------
  test("INSERT INTO") {
    CarbonProperties.getInstance().addProperty(CarbonCommonConstants.LOAD_SORT_SCOPE, "GLOBAL_SORT")
    CarbonProperties.getInstance().addProperty(CarbonCommonConstants.LOAD_GLOBAL_SORT_PARTITIONS, "2")
    sql(s"INSERT INTO TABLE carbon_globalsort SELECT * FROM carbon_localsort_once")

    assert(getIndexFileCount("carbon_globalsort") === 2)
    checkAnswer(sql("SELECT COUNT(*) FROM carbon_globalsort"), Seq(Row(12)))
    checkAnswer(sql("SELECT * FROM carbon_globalsort ORDER BY name"),
      sql("SELECT * FROM carbon_localsort_once ORDER BY name"))
  }

  test("Test with different date types") {
    val path = s"$projectPath/examples/spark2/src/main/resources/data.csv"

    sql("DROP TABLE IF EXISTS carbon_localsort_difftypes")
    sql(
      s"""
         | CREATE TABLE carbon_localsort_difftypes(
         | shortField smallint,
         | intField INT,
         | bigintField bigint,
         | doubleField DOUBLE,
         | stringField STRING,
         | timestampField TIMESTAMP,
         | decimalField DECIMAL(18,2),
         | dateField DATE,
         | charField CHAR(5),
         | floatField FLOAT
         | )
         | STORED BY 'org.apache.carbondata.format'
       """.stripMargin)
    sql(
      s"""
         | LOAD DATA LOCAL INPATH '$path' INTO TABLE carbon_localsort_difftypes
         | OPTIONS('FILEHEADER'='shortField,intField,bigintField,doubleField,stringField,timestampField,decimalField,dateField,charField,floatField')
       """.stripMargin)

    sql("DROP TABLE IF EXISTS carbon_globalsort_difftypes")
    sql(
      s"""
         | CREATE TABLE carbon_globalsort_difftypes(
         | shortField smallint,
         | intField INT,
         | bigintField bigint,
         | doubleField DOUBLE,
         | stringField STRING,
         | timestampField TIMESTAMP,
         | decimalField DECIMAL(18,2),
         | dateField DATE,
         | charField CHAR(5),
         | floatField FLOAT
         | )
         | STORED BY 'org.apache.carbondata.format'
       """.stripMargin)
    sql(
      s"""
         | LOAD DATA LOCAL INPATH '$path' INTO TABLE carbon_globalsort_difftypes
         | OPTIONS('SORT_SCOPE'='GLOBAL_SORT',
         | 'FILEHEADER'='shortField,intField,bigintField,doubleField,stringField,timestampField,decimalField,dateField,charField,floatField')
       """.stripMargin)

    checkAnswer(sql("SELECT * FROM carbon_globalsort_difftypes ORDER BY shortField"),
      sql("SELECT * FROM carbon_localsort_difftypes ORDER BY shortField"))
  }

  private def resetConf() {
    CarbonProperties.getInstance()
      .addProperty(CarbonCommonConstants.LOAD_SORT_SCOPE, CarbonCommonConstants.LOAD_SORT_SCOPE_DEFAULT)
    CarbonProperties.getInstance()
      .addProperty(CarbonCommonConstants.LOAD_GLOBAL_SORT_PARTITIONS,
        CarbonCommonConstants.LOAD_GLOBAL_SORT_PARTITIONS_DEFAULT)

    sql(s"SET ${CarbonCommonConstants.LOAD_SORT_SCOPE} = ${CarbonCommonConstants.LOAD_SORT_SCOPE_DEFAULT}")
    sql(s"SET ${CarbonCommonConstants.LOAD_GLOBAL_SORT_PARTITIONS} = " +
      s"${CarbonCommonConstants.LOAD_GLOBAL_SORT_PARTITIONS_DEFAULT}")
  }

  private def getIndexFileCount(tableName: String, segmentNo: String = "0"): Int = {
    val store  = storeLocation + "/default/" + tableName + "/Fact/Part0/Segment_" + segmentNo
    val list = new File(store).list(new FilenameFilter {
      override def accept(dir: File, name: String) = name.endsWith(".carbonindex")
    })
    list.size
  }
}
