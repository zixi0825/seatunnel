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
package io.github.interestinglab.waterdrop.spark.source

import io.github.interestinglab.waterdrop.spark.SparkEnvironment
import io.github.interestinglab.waterdrop.spark.batch.SparkBatchSource
import com.alibaba.fastjson.JSON
import com.mongodb.spark.MongoSpark
import com.mongodb.spark.config.ReadConfig
import io.github.interestinglab.waterdrop.common.config.{CheckResult, TypesafeConfigUtils}
import io.github.interestinglab.waterdrop.spark.utils.SparkSturctTypeUtil
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{Dataset, Row}

import scala.collection.JavaConversions._

class MongoDB extends SparkBatchSource {

  var readConfig: ReadConfig = _

  val confPrefix = "readconfig."

  var schema = new StructType()

  override def prepare(env: SparkEnvironment): Unit = {
    val map = new collection.mutable.HashMap[String, String]

    TypesafeConfigUtils
      .extractSubConfig(config, confPrefix, false)
      .entrySet()
      .foreach(entry => {
        val key = entry.getKey
        val value = String.valueOf(entry.getValue.unwrapped())
        map.put(key, value)
      })
    config.hasPath("schema") match {
      case true => {
        val schemaJson = JSON.parseObject(config.getString("schema"))
        schema = SparkSturctTypeUtil.getStructType(schema, schemaJson)
      }
      case false => {}
    }
    readConfig = ReadConfig(map)
  }

  override def getData(env: SparkEnvironment): Dataset[Row] = {
    if (schema.length > 0) {
      MongoSpark.builder().sparkSession(env.getSparkSession).readConfig(readConfig).build().toDF(schema)
    } else {
      MongoSpark.load(env.getSparkSession, readConfig);
    }
  }

  override def checkConfig(): CheckResult = {
    TypesafeConfigUtils.hasSubConfig(config, confPrefix) match {
      case true => {
        val read = TypesafeConfigUtils.extractSubConfig(config, confPrefix, false)
        read.hasPath("uri") && read.hasPath("database") && read.hasPath("collection") match {
          case true => new CheckResult(true,"")
          case false => new CheckResult(false, "please specify [readconfig.uri] and [readconfig.database] and [readconfig.collection]")
        }
      }
      case false => new CheckResult(false, "please specify [readconfig]")
    }
  }

}
