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

package org.apache.spark.ml.tuning.bandit

import org.apache.spark.mllib.linalg.{DenseVector, Vectors}
import org.apache.spark.mllib.linalg.BLAS._

import scala.collection.mutable

case class ArmInfo(dataName: String, numArms: Int, maxIter: Int, trial: Int)

abstract class Search {
  val name: String
  val results = new mutable.HashMap[ArmInfo, Array[Arm[_]]]()

  def appendResults(armInfo: ArmInfo, arms: Array[Arm[_]]) = {
    results(armInfo) = arms
  }

  def search(totalBudgets: Int, arms: Map[(String, String), Arm[_]]): Arm[_]
}

class StaticSearch extends Search {
  override val name = "static search"
  override def search(totalBudgets: Int, arms: Map[(String, String), Arm[_]]): Arm[_] = {

    assert(arms.keys.size != 0, "ERROR: No arms!")
    val armValues = arms.values.toArray
    val numArms = arms.keys.size
    var i = 0
    while (i  < totalBudgets) {
      armValues(i % numArms).pull()
      i += 1
    }

    val bestArm = armValues.maxBy(arm => arm.getValidationResult())
    bestArm
  }
}

class SimpleBanditSearch extends Search {
  override val name = "simple bandit search"
  override def search(totalBudgets: Int, arms: Map[(String, String), Arm[_]]): Arm[_] = {
    val numArms = arms.size
    val alpha = 0.3
    val initialRounds = math.max(1, (alpha * totalBudgets / numArms).toInt)

    val armValues = arms.values.toArray

    for (i <- 0 until initialRounds) {
      armValues.foreach(_.pull())
    }

    var currentBudget = initialRounds * numArms
    val numPreSelectedArms = math.max(1, (alpha * numArms).toInt)

    val preSelectedArms = armValues.sortBy(_.getValidationResult())
      .reverse.dropRight(numArms - numPreSelectedArms)

    while (currentBudget < totalBudgets) {
      preSelectedArms(currentBudget % numPreSelectedArms).pull()
      currentBudget += 1
    }

    val bestArm = preSelectedArms.maxBy(arm => arm.getValidationResult())
    bestArm
  }
}

class ExponentialWeightsSearch extends Search {
  override val name = "exponential weight search"
  override def search(totalBudgets: Int, arms: Map[(String, String), Arm[_]]): Arm[_] = {
    val numArms = arms.size
    val armValues = arms.values.toArray
    val eta = math.sqrt(2 * math.log(numArms) / (numArms * totalBudgets))

    val lt = Vectors.zeros(numArms).asInstanceOf[DenseVector]
    val wt = Vectors.dense(Array.fill(numArms)(1.0)).asInstanceOf[DenseVector]
    for (t <- 0 until totalBudgets) {
      val pt = Vectors.zeros(numArms)
      axpy(Utils.sum(wt), wt, pt)
      val it = if (t < numArms) t else Utils.chooseOne(pt)
      val arm = armValues(it)
      arm.pull()
      lt.values(it) += arm.getValidationResult()
      wt.values(it) = math.exp(- eta * lt(it))
    }
    val bestArm = armValues.maxBy(arm => arm.getValidationResult())
    bestArm
  }
}

class LILUCBSearch extends Search {
  override val name = "law of iterated logarithm upper confidence bound search"
  override def search(totalBudgets: Int, arms: Map[(String, String), Arm[_]]): Arm[_] = {
    val numArms = arms.size
    val armValues = arms.values.toArray

    val nj = Vectors.zeros(numArms).asInstanceOf[DenseVector]
    val sumj = Vectors.zeros(numArms).asInstanceOf[DenseVector]
    for (i <- 0 until numArms) {
      armValues(i).pull()
      sumj.values(i) += armValues(i).getValidationResult()
      nj.values(i) += 1
    }

    val delta = 0.1
    var t = numArms
    val ct = Vectors.zeros(numArms).asInstanceOf[DenseVector]
    copy(nj, ct)
    scal(3.0, ct)
    Utils.log(ct)
    scal(5.0 / delta, ct)
    Utils.log(ct)
    scal(0.5, ct)
    Utils.div(nj, ct)
    Utils.sqrt(ct)
    scal(1.5, ct)

    val ucbj = Vectors.zeros(numArms).asInstanceOf[DenseVector]
    copy(sumj, ucbj)
    Utils.div(nj, ucbj)
    Utils.sub(ct, ucbj)

    while (t < totalBudgets) {
      val it = Utils.argMin(ucbj)
      armValues(it).pull()
      sumj.values(it) += armValues(it).getValidationResult()
      nj.values(it) += 1
      ct.values(it) = 1.5 * math.sqrt(0.5 * math.log(5.0 * math.log(3.0 * nj(it)) / delta) / nj(it))
      ucbj.values(it) = sumj(it) / nj(it) - ct(it)
      t += 1
    }

    val bestArm = armValues.maxBy(arm => arm.getValidationResult(false))
    bestArm
  }
}

class LUCBSearch extends Search {
  override val name = "LUCB search"
  override def search(totalBudgets: Int, arms: Map[(String, String), Arm[_]]): Arm[_] = {
    val numArms = arms.size
    val armValues = arms.values.toArray

    val nj = new Array[Double](numArms)
    val sumj = new Array[Double](numArms)
    for (i <- 0 until numArms) {
      armValues(i).pull()
      sumj(i) += armValues(i).getResults(true, Some("validation"))(1)
      nj(i) += 1
    }

    val delta = 0.1
    var t = numArms
    val ct = nj.map(x => 1.5 * math.sqrt(0.5 * math.log(math.log(3.0 * x) / delta) / x))
    val ucbj = sumj.zip(nj).zip(ct).map { case ((sj, j), c) => sj / j - c}

    while (t + 2 <= totalBudgets) {
      val inds0 = argSort(sumj.zip(nj).map {case (sj, j) => sj / j})
      val inds1 = argSort(ucbj)

      var it = inds0(0)
      armValues(it).pull()
      sumj(it) += armValues(it).getResults(true, Some("validation"))(1)
      nj(it) += 1
      t += 1

      val k1 = 1.25
      val t2nd = math.max(t * t / 4.0, 1.0)
      val t4th = t2nd * t2nd
      var ct = math.sqrt(0.5 * math.log(k1 * numArms * t4th / delta) / armValues(it).numPulls)
      ucbj(it) = sumj(it) / nj(it) - ct

      it = if (inds1(0) == inds0(0)) inds1(1) else inds1(0)
      armValues(it).pull()
      sumj(it) += armValues(it).getResults(true, Some("validation"))(1)
      nj(it) += 1
      t += 1
      ct = math.sqrt(0.5 * math.log(k1 * numArms * t4th / delta) / armValues(it).numPulls)
      ucbj(it) = sumj(it) / nj(it) - ct
    }

    val bestArm = armValues.maxBy(arm => arm.getResults(false, Some("validation"))(1))
    bestArm
  }
}

