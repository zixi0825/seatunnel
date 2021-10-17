package io.github.interestinglab.waterdrop.spark.batch

import java.util.{List => JList}

import io.github.interestinglab.waterdrop.config.{Config, ConfigFactory}
import io.github.interestinglab.waterdrop.common.config.{CheckResult, ConfigRuntimeException}
import io.github.interestinglab.waterdrop.env.Execution
import io.github.interestinglab.waterdrop.spark.{BaseSparkSink, BaseSparkSource, BaseSparkTransform, SparkEnvironment}
import org.apache.spark.sql.{Dataset, Row}

import scala.collection.JavaConversions._

class SparkBatchExecution(environment: SparkEnvironment)
  extends Execution[SparkBatchSource, BaseSparkTransform, SparkBatchSink] {

  private var config = ConfigFactory.empty()

  override def setConfig(config: Config): Unit = this.config = config

  override def getConfig: Config = config

  override def checkConfig(): CheckResult = new CheckResult(true, "")

  override def prepare(prepareEnv: Void): Unit = {}

  override def start(sources: JList[SparkBatchSource],
                     transforms: JList[BaseSparkTransform],
                     sinks: JList[SparkBatchSink]): Unit = {

    sources.foreach(s => SparkEnvironment.registerInputTempView(s, environment))

    if (!sources.isEmpty) {
      var ds = sources.get(0).getData(environment)
      for (tf <- transforms) {

        if (ds.take(1).length > 0) {
          ds = SparkEnvironment.transformProcess(environment, tf, ds)
          SparkEnvironment.registerTransformTempView(tf, ds)
        }
      }

      sinks.foreach(sink => SparkEnvironment.sinkProcess(environment, sink, ds))
    }
  }

}

