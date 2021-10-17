package io.github.interestinglab.waterdrop.spark

import io.github.interestinglab.waterdrop.config.{Config, ConfigFactory}
import io.github.interestinglab.waterdrop.common.config.{CheckResult, ConfigRuntimeException}
import io.github.interestinglab.waterdrop.common.enums.JobMode
import io.github.interestinglab.waterdrop.env.RuntimeEnv
import io.github.interestinglab.waterdrop.plugin.Plugin
import org.apache.spark.SparkConf
import org.apache.spark.sql.{Dataset, Row, SparkSession}
import org.apache.spark.streaming.{Seconds, StreamingContext}

import scala.collection.JavaConversions._

class SparkEnvironment extends RuntimeEnv {

  private var sparkSession: SparkSession = _

  private var streamingContext: StreamingContext = _
  
  var config: Config = ConfigFactory.empty()

  override def setConfig(config: Config): Unit = this.config = config

  override def getConfig: Config = config

  override def checkConfig(): CheckResult = new CheckResult(true, "")

  override def prepare(mode: JobMode): Unit = {
    val sparkConf = createSparkConf()
    sparkSession = SparkSession.builder().config(sparkConf).getOrCreate()
    if (JobMode.STREAMING.equals(mode)) {
      createStreamingContext
    }
  }

  private def createSparkConf(): SparkConf = {
    val sparkConf = new SparkConf()
    config
      .entrySet()
      .foreach(entry => {
        sparkConf.set(entry.getKey, String.valueOf(entry.getValue.unwrapped()))
      })

    sparkConf
  }

  private def createStreamingContext: StreamingContext = {
    val conf = sparkSession.sparkContext.getConf
    val duration = conf.getLong("spark.stream.batchDuration", 5)
    if (streamingContext == null) {
      streamingContext =
        new StreamingContext(sparkSession.sparkContext, Seconds(duration))
    }
    streamingContext
  }

  def getStreamingContext: StreamingContext = {
    streamingContext
  }

  def getSparkSession: SparkSession = {
    sparkSession
  }

}

object SparkEnvironment {


  private[waterdrop] def registerTempView(tableName: String, ds: Dataset[Row]): Unit = {
    ds.createOrReplaceTempView(tableName)
  }

  private[waterdrop] def registerInputTempView[S <: BaseSparkSource[Dataset[Row]]](source: S, environment: SparkEnvironment): Unit = {
    val conf = source.getConfig
    conf.hasPath(Plugin.RESULT_TABLE_NAME) match {
      case true => {
        val tableName = conf.getString(Plugin.RESULT_TABLE_NAME)
        registerTempView(tableName, source.getData(environment))
      }
      case false => {
        throw new ConfigRuntimeException(
          "Plugin[" + source.getClass.getName + "] must be registered as dataset/table, please set \"result_table_name\" config")

      }
    }
  }

  private[waterdrop] def transformProcess(environment: SparkEnvironment, transform: BaseSparkTransform, ds: Dataset[Row]): Dataset[Row] = {
    val config = transform.getConfig()
    val fromDs = config.hasPath(Plugin.SOURCE_TABLE_NAME) match {
      case true => {
        val sourceTableName = config.getString(Plugin.SOURCE_TABLE_NAME)
        environment.getSparkSession.read.table(sourceTableName)
      }
      case false => ds
    }

    transform.process(fromDs, environment)
  }

  private[waterdrop] def registerTransformTempView(plugin: BaseSparkTransform, ds: Dataset[Row]): Unit = {
    val config = plugin.getConfig()
    if (config.hasPath(Plugin.RESULT_TABLE_NAME)) {
      val tableName = config.getString(Plugin.RESULT_TABLE_NAME)
      registerTempView(tableName, ds)
    }
  }

  private[waterdrop] def sinkProcess(environment: SparkEnvironment, sink: BaseSparkSink[_], ds: Dataset[Row]): Unit = {
    val config = sink.getConfig()
    val fromDs = config.hasPath(Plugin.SOURCE_TABLE_NAME) match {
      case true => {
        val sourceTableName = config.getString(Plugin.SOURCE_TABLE_NAME)
        environment.getSparkSession.read.table(sourceTableName)
      }
      case false => ds
    }

    sink.output(fromDs, environment)
  }
}
