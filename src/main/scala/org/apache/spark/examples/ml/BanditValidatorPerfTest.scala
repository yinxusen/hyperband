package org.apache.spark.examples.ml

import org.apache.spark.ml.classification.LogisticRegression
import org.apache.spark.ml.evaluation.BinaryClassificationEvaluator
import org.apache.spark.ml.tuning.ParamGridBuilder
import org.apache.spark.ml.tuning.bandit.{BanditValidator, ExponentialWeightsSearch, StaticSearch}
import org.apache.spark.mllib.linalg.Vector
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.util.MLUtils
import org.apache.spark.sql.SQLContext
import org.apache.spark.{SparkConf, SparkContext}

/**
  * Created by panda on 1/21/16.
  */
object BanditValidatorPerfTest {
  def main(args: Array[String]): Unit = {
    val conf = new SparkConf().setMaster("local[8]").setAppName("BanditPerfTest")

    val sc = new SparkContext(conf)
    val sqlCtx = new SQLContext(sc)
    import sqlCtx.implicits._

    //val data = MLUtils.loadLibSVMFile(sc, "/Users/panda/data/small_datasets/adult.tst").map {
    val data = MLUtils.loadLibSVMFile(sc, "/Users/panda/data/small_datasets/australian", -1, 2).map {
      case LabeledPoint(label: Double, features: Vector) =>
        LabeledPoint(if (label < 0) 0 else label, features)
    }

    val dataset = data.toDF()

    val splits = dataset.randomSplit(Array(0.7, 0.3))
    val training = splits(0).cache()
    val test = splits(1).cache()

    val singleClassifier = new LogisticRegression().setMaxIter(3)

    val params = new ParamGridBuilder()
      .addGrid(singleClassifier.elasticNetParam, Array(1.0, 0.1, 0.01))
      .addGrid(singleClassifier.regParam, Array(0.1, 0.01))
      .addGrid(singleClassifier.fitIntercept, Array(true, false))
      .build()

    val eval = new BinaryClassificationEvaluator().setMetricName("areaUnderROC")

    val banditVal = new BanditValidator()
      .setEstimator(singleClassifier)
      .setEstimatorParamMaps(params)
      .setNumFolds(3)
      .setEvaluator(eval)

    val pathPrefix = "/Users/panda/data/small_datasets/australian-results-fixed"

    Array(
      new StaticSearch,
      //new NaiveSearch,
      //new SuccessiveEliminationSearch,
      new ExponentialWeightsSearch
      //new LILUCBSearch,
      //new LUCBSearch,
      //new SuccessiveHalvingSearch,
      //new SuccessiveRejectSearch
    ).foreach { search =>
      banditVal.setSearchStrategy(search)
      val model = banditVal.fit(training)
      val auc = eval.evaluate(model.transform(test))

      val part1 = s"${search.getClass.getSimpleName} -> ${auc}"
      val part2 = banditVal.readableSummary()
      val part3 = banditVal.paintableSummary()

      sc.parallelize(Array(part1) ++ part2).repartition(1)
        .saveAsTextFile(s"${pathPrefix}/result-${search.getClass.getSimpleName}")

      part3.foreach { case (fold, result) =>
        val strResult = result.map { case (iter, arm, training, validation) =>
          s"$iter,$arm,$training,$validation"
        }
        sc.parallelize(strResult).repartition(1)
          .saveAsTextFile(s"${pathPrefix}/result-${search.getClass.getSimpleName}/fold-${fold}")
      }
    }
    sc.stop()
  }
}
