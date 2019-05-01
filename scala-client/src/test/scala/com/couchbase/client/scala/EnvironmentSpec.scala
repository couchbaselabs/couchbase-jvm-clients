package com.couchbase.client.scala

import com.couchbase.client.core.env.SaslMechanism
import com.couchbase.client.scala.env._
import org.junit.jupiter.api.Test

import scala.concurrent.duration.Duration
import scala.util.Success

class EnvironmentSpec {
  @Test
  def basic() {
    val env = ClusterEnvironment.builder("localhost", "Administrator", "password").build
    val cluster = Cluster.connect(env)
    cluster.shutdown()
    env.shutdown()
  }

  @Test
  def buildSafe() {
    ClusterEnvironment.builder("localhost", "Administrator", "password").buildSafe match {
      case Success(env) =>
        val cluster = Cluster.connect(env)
        cluster.shutdown()
        env.shutdown()
      case _ => assert(false)
    }
  }

  @Test
  def basic_unowned() {
    val env = ClusterEnvironment.builder("localhost", "Administrator", "password").build
    val cluster = Cluster.connect(env)
    assert(!env.owned)
    cluster.shutdown()
    env.shutdown()
    assert(env.threadPool.isShutdown)
    assert(env.threadPool.isTerminated)
  }

  @Test
  def basic_owned() {
    val cluster = Cluster.connect("localhost", "Administrator", "password")
    val env = cluster.async.env
    assert(env.owned)
    cluster.shutdown()
    assert(env.threadPool.isShutdown)
    assert(env.threadPool.isTerminated)
  }

  @Test
  def io_env() {
    val env = ClusterEnvironment.builder("localhost", "Administrator", "password")
      .ioEnvironment(IoEnvironment()
        .managerEventLoopGroup(null)
        .analyticsEventLoopGroup(null))
      .build
    env.shutdown()
  }

  @Test
  def io_config() {
    val env = ClusterEnvironment.builder("localhost", "Administrator", "password")
      .ioConfig(IoConfig()
        .mutationTokensEnabled(true)
        .allowedSaslMechanisms(Set(SaslMechanism.PLAIN, SaslMechanism.CRAM_MD5))
        .configPollInterval(Duration("5 seconds"))
        .kvCircuitBreakerConfig(CircuitBreakerConfig()
          .enabled(true)
          .errorThresholdPercentage(50)
          .sleepWindow(Duration("10 seconds"))
        )
      )

      .build
    env.shutdown()
  }

  @Test
  def service_config() {
    val env = ClusterEnvironment.builder("localhost", "Administrator", "password")
      .serviceConfig(ServiceConfig()
        .keyValueServiceConfig(KeyValueServiceConfig()
          .endpoints(5))
        .queryServiceConfig(QueryServiceConfig()
          .maxEndpoints(10)
          .minEndpoints(3))
      )
      .build
    env.shutdown()
  }

  @Test
  def logging_config() {
    val env = ClusterEnvironment.builder("localhost", "Administrator", "password")
      .loggerConfig(LoggerConfig()
        .loggerName("test")
        .fallbackToConsole(true)
        .disableSlf4J(true)
      )
      .build
    env.shutdown()
  }
}