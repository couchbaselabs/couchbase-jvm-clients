/*
 * Copyright (c) 2019 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.couchbase.client.scala


import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import com.couchbase.client.core.Core
import com.couchbase.client.core.env.Credentials
import com.couchbase.client.core.error.QueryServiceException
import com.couchbase.client.scala.env.ClusterEnvironment
import com.couchbase.client.scala.query._
import com.couchbase.client.scala.query.handlers.QueryHandler
import com.couchbase.client.scala.util.FutureConversions

import scala.collection.JavaConverters._
import scala.compat.java8.OptionConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/** Represents a connection to a Couchbase cluster.
  *
  * This is the asynchronous version of the [[Cluster]] API.
  *
  * These can be created through the functions in the companion object, or through [[Cluster.async]].
  *
  * @param environment the environment used to create this
  * @param ec          an ExecutionContext to use for any Future.  Will be supplied automatically as long as
  *                    resources are
  *                    opened in the normal way, starting from functions in [[Cluster]]
  *
  * @author Graham Pople
  * @since 1.0.0
  */
class AsyncCluster(environment: => ClusterEnvironment)
                  (implicit ec: ExecutionContext) {
  private[scala] val core = Core.create(environment)
  private[scala] val env = environment

  private[scala] val queryHandler = new QueryHandler()

  /** Opens and returns a Couchbase bucket resource that exists on this cluster.
    *
    * @param name the name of the bucket to open
    */
  def bucket(name: String): Future[AsyncBucket] = {
    FutureConversions.javaMonoToScalaFuture(core.openBucket(name))
      .map(v => new AsyncBucket(name, core, environment))
  }

  /** Performs a N1QL query against the cluster.
    *
    * This is asynchronous.  See [[Cluster.reactive]] for a reactive streaming version of this API, and
    * [[Cluster]] for a blocking version.
    *
    * @param statement the N1QL statement to execute
    * @param options   any query options - see [[QueryOptions]] for documentation
    *
    * @return a `Future` containing a `Success(QueryResult)` (which includes any returned rows) if successful, else a
    *         `Failure`
    */
  def query(statement: String, options: QueryOptions = QueryOptions()): Future[QueryResult] = {

    queryHandler.request(statement, options, core, environment) match {
      case Success(request) =>
        core.send(request)

        import reactor.core.scala.publisher.{Mono => ScalaMono}
        val rowsKeeper = new AtomicReference[Seq[QueryRow]]()

        val ret: Future[QueryResult] = FutureConversions.javaCFToScalaMono(request, request.response(),
          propagateCancellation = true)
          .flatMap(response => FutureConversions.javaFluxToScalaFlux(response.rows)
            .collectSeq()
            .flatMap(rows => {
              rowsKeeper.set(rows.map(QueryRow))

              FutureConversions.javaMonoToScalaMono(response.additional())
            })
            .map(addl => QueryResult(
              rowsKeeper.get(),
              response.requestId(),
              response.clientContextId().asScala,
              QuerySignature(response.signature().asScala),
              QueryMetrics.fromBytes(addl.metrics),
              addl.warnings.asScala.map(QueryError),
              addl.status,
              addl.profile.asScala.map(QueryProfile))
            )
          )
          .onErrorResume(err => {
            err match {
              case e: QueryServiceException => ScalaMono.error(QueryError(e.content))
              case _ => ScalaMono.error(err)
            }
          }).toFuture

        ret.failed.foreach(err => {
          println(s"scala future error ${err}")
        })

        ret


      case Failure(err) => Future.failed(err)
    }
  }

  /** Shutdown all cluster resources.
    *
    * This should be called before application exit.
    */
  def shutdown(): Future[Unit] = {
    Future {
      environment.shutdown(environment.timeoutConfig().disconnectTimeout())
    }
  }
}

/** Functions to allow creating an `AsyncCluster`, which represents a connection to a Couchbase cluster.
  *
  * @define DeferredErrors Note that during opening of resources, all errors will be deferred until the first
  *                        attempted operation.
  */
object AsyncCluster {
  private implicit val ec = Cluster.ec

  /**
    * Connect to a Couchbase cluster with a username and a password as credentials.
    *
    * $DeferredErrors
    *
    * @param connectionString connection string used to locate the Couchbase cluster.
    * @param username         the name of a user with appropriate permissions on the cluster.
    * @param password         the password of a user with appropriate permissions on the cluster.
    *
    * @return a [[AsyncCluster]] representing a connection to the cluster
    */
  def connect(connectionString: String, username: String, password: String): Future[AsyncCluster] = {
    Future {
      Cluster.connect(connectionString, username, password).async
    }
  }

  /**
    * Connect to a Couchbase cluster with custom [[Credentials]].
    *
    * $DeferredErrors
    *
    * @param connectionString connection string used to locate the Couchbase cluster.
    * @param credentials      custom credentials used when connecting to the cluster.
    *
    * @return a [[AsyncCluster]] representing a connection to the cluster
    */
  def connect(connectionString: String, credentials: Credentials): Future[AsyncCluster] = {
    Future {
      Cluster.connect(connectionString, credentials).async
    }
  }

  /**
    * Connect to a Couchbase cluster with a custom [[ClusterEnvironment]].
    *
    * $DeferredErrors
    *
    * @param environment the custom environment with its properties used to connect to the cluster.
    *
    * @return a [[AsyncCluster]] representing a connection to the cluster
    */
  def connect(environment: ClusterEnvironment): Future[AsyncCluster] = {
    Future {
      Cluster.connect(environment).async
    }
  }
}