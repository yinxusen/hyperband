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

import org.apache.spark.rdd.{PartitionwiseSampledRDD, RDD}
import org.apache.spark.util.random.BernoulliCellSampler

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

object Utils {
  def splitTrainTest[T: ClassTag](rdd: RDD[T], testFraction: Double, seed: Int): (RDD[T], RDD[T]) = {
    val sampler = new BernoulliCellSampler[T](0, testFraction, complement = false)
    val test = new PartitionwiseSampledRDD(rdd, sampler, true, seed)
    val training = new PartitionwiseSampledRDD(rdd, sampler.cloneComplement(), true, seed)
    (training, test)
  }
}

/**
 * Allocate an array of pre-generated arms for a [SearchStrategy].
 */
class ArmsAllocator(val allArms: Map[(String, String), Arms.ArmExistential]) {
  val usedArms = new ArrayBuffer[(String, String)]()
  val unusedArms = new ArrayBuffer[(String, String)]()
  unusedArms.appendAll(allArms.keys)
  val arms = new mutable.HashMap[(String, String), Arms.ArmExistential]()

  def allocate(numArms: Int): Map[(String, String), Arms.ArmExistential] = {
    assert(numArms <= allArms.size,
      s"Required $numArms arms exceed the total amount ${allArms.size}.")
    val arms = new mutable.HashMap[(String, String), Arms.ArmExistential]()
    var i = 0
    while (i < math.min(numArms, usedArms.size)) {
      arms += usedArms(i) -> allArms(usedArms(i))
      i += 1
    }
    while (i < numArms) {
      val armInfo = unusedArms.remove(0)
      arms += armInfo -> allArms(armInfo)
      usedArms.append(armInfo)
      i += 1
    }
    arms.toMap.mapValues(_.reset())
  }
}
