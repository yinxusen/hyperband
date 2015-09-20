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

package org.apache.spark.examples

import org.apache.spark.sql.SQLContext
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.ml.tuning.bandit._

object BanditValidatorExample {
  def main(args: Array[String]): Unit = {

    val params: Array[ParamSampler[_]] = Array(new DoubleParamSampler("regularizer", -6, 0))

    val lrrGenerator = new LinearRidgeRegressionArmFactory("linear ridge regression family", params)

    val staticSearch= new StaticSearch

    val simpleSearch= new SimpleBanditSearch

    val exp3 = new ExponentialWeightsSearch

    val libucb = new LILUCBSearch

    val lucb = new LUCBSearch

    val sh = new SuccessiveHalving

    val banditValidator = new BanditValidator()
      .setProblemType("REG")
      .setDatasets(Map("msd" -> "/Users/panda/data/msd_trunc.libsvm"))
      .setComputeHistory(false)
      .setExpectedIters(Array(10))
      .setNumArmsList(Array(20))
      .setSeed(1066)
      .setNumTrails(2)
      .setStepsPerPulling(1)
      .setArmFactories(Array(lrrGenerator))
      .setSearchStrategies(Array(exp3, libucb, lucb, sh))

    val conf = new SparkConf()
      .setMaster("local[4]")
      .setAppName("multi-arm bandit hyper-parameter selection")
    val sc = new SparkContext(conf)
    val sqlCtx = new SQLContext(sc)
    val results = banditValidator.fit(sqlCtx)

    for (((ssName, dataName, numArms, iterPerArm), bestArmResults)<- results) {
      println(s"Search strategy $ssName, data $dataName, number of arms $numArms, iteration per" +
        s" arm $iterPerArm.\t\tBest result is ${bestArmResults.mkString(", ")}")
    }
  }
}
