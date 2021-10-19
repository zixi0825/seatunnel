package io.github.interestinglab.waterdrop.spark.stream

import java.util.{List => JList}
import io.github.interestinglab.waterdrop.config.{Config, ConfigFactory}
import io.github.interestinglab.waterdrop.common.config.CheckResult
import io.github.interestinglab.waterdrop.env.Execution
import io.github.interestinglab.waterdrop.plugin.Plugin
import io.github.interestinglab.waterdrop.spark.{BaseSparkSink, BaseSparkSource, BaseSparkTransform, SparkEnvironment}
import io.github.interestinglab.waterdrop.spark.batch.SparkBatchExecution
import org.apache.spark.sql.{Dataset, Row}

import scala.collection.JavaConversions._

class SparkStreamingExecution(sparkEnvironment: SparkEnvironment) extends Execution[BaseSparkSource[_], BaseSparkTransform, BaseSparkSink[_]] {

  private var config = ConfigFactory.empty()

  override def start(sources: JList[BaseSparkSource[_]],
                     transforms: JList[BaseSparkTransform],
                     sinks: JList[BaseSparkSink[_]]): Unit = {

    val source = sources.get(0).asInstanceOf[SparkStreamingSource[_]]

    sources.subList(1, sources.size()).foreach(s => {
      SparkEnvironment.registerInputTempView(s.asInstanceOf[BaseSparkSource[Dataset[Row]]], sparkEnvironment)
    })

    source.start(sparkEnvironment, dataset => {
      val conf = source.getConfig
      if (conf.hasPath(Plugin.RESULT_TABLE_NAME)) {
        SparkEnvironment.registerTempView(conf.getString(Plugin.RESULT_TABLE_NAME), dataset)
      }
      var ds = dataset
      for (tf <- transforms) {
        if (ds.take(1).length > 0) {
          ds = SparkEnvironment.transformProcess(sparkEnvironment, tf, ds)
          SparkEnvironment.registerTransformTempView(tf, ds)
        }
      }

      source.beforeOutput

      if (ds.take(1).length > 0) {
        sinks.foreach(sink => {
          SparkEnvironment.sinkProcess(sparkEnvironment, sink, ds)
        })
      }

      source.afterOutput
    })

    val streamingContext = sparkEnvironment.getStreamingContext
    streamingContext.start()
    streamingContext.awaitTermination()
  }

  override def setConfig(config: Config): Unit = this.config = config

  override def getConfig: Config = config

  override def checkConfig(): CheckResult = new CheckResult(true, "")

  override def prepare(void: Void): Unit = {}
}
