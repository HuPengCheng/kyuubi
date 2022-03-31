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

package org.apache.kyuubi.plugin.spark.authz.ranger

import scala.collection.mutable.ArrayBuffer

import org.apache.hadoop.security.UserGroupInformation
import org.apache.spark.SparkContext
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.rules.Rule

import org.apache.kyuubi.plugin.spark.authz.{ranger, ObjectType, OperationType, PrivilegeObject, PrivilegesBuilder}
import org.apache.kyuubi.plugin.spark.authz.ObjectType._

class RangerSparkAuthorizer(spark: SparkSession) extends Rule[LogicalPlan] {
  override def apply(plan: LogicalPlan): LogicalPlan = {
    RangerSparkAuthorizer.checkPrivileges(spark, plan)
    plan
  }
}

object RangerSparkAuthorizer {

  /**
   * Get the active session user
   * @param spark spark context instance
   * @return the user name
   */
  private def getAuthzUgi(spark: SparkContext): UserGroupInformation = {
    // kyuubi.session.user is only used by kyuubi
    val user = spark.getLocalProperty("kyuubi.session.user")
    if (user != null) {
      UserGroupInformation.createRemoteUser(user)
    } else {
      UserGroupInformation.getCurrentUser
    }
  }

  def checkPrivileges(
      spark: SparkSession,
      plan: LogicalPlan): Unit = {
    val ugi = getAuthzUgi(spark.sparkContext)
    val opType = OperationType(plan.nodeName)
    val (inputs, outputs) = PrivilegesBuilder.build(plan)
    val requests = new ArrayBuffer[AccessRequest]()
    if (inputs.isEmpty && opType == OperationType.SHOWDATABASES) {
      val resource = AccessResource(DATABASE, null)
      requests += AccessRequest(resource, ugi, opType.toString, AccessType.USE)
    }

    def addAccessRequest(objects: Seq[PrivilegeObject], isInput: Boolean): Unit = {
      objects.foreach { obj =>
        val resource = AccessResource(obj, opType)
        val accessType = ranger.AccessType(obj, opType, isInput)
        if (accessType != AccessType.NONE && !requests.exists(o =>
            o.accessType == accessType && o.getResource == resource)) {
          requests += AccessRequest(resource, ugi, opType.toString, accessType)
        }
      }
    }

    addAccessRequest(inputs, isInput = true)
    addAccessRequest(outputs, isInput = false)

    requests.foreach { request =>
      val resource = request.getResource.asInstanceOf[AccessResource]
      resource.objectType match {
        case ObjectType.COLUMN if resource.getColumns.nonEmpty =>
          resource.getColumns.foreach { col =>
            val cr = AccessResource(COLUMN, resource.getDatabase, resource.getTable, col)
            val req = AccessRequest(cr, ugi, opType.toString, request.accessType)
            verify(req)
          }
        case _ => verify(request)
      }
    }
  }

  private def verify(req: AccessRequest): Unit = {
    val ret = RangerSparkPlugin.isAccessAllowed(req, null)
    if (ret != null && !ret.getIsAllowed) {
      throw new RuntimeException(
        s"Permission denied: user [${req.getUser}] does not have [${req.getAccessType}] privilege" +
          s" on [${req.getResource.getAsString}]")
    }
  }
}