package io.github.crabzilla.example1

import com.zaxxer.hikari.HikariDataSource
import io.github.crabzilla.example1.customer.ActivateCustomer
import io.github.crabzilla.example1.customer.CreateCustomer
import io.github.crabzilla.example1.customer.Customer
import io.github.crabzilla.example1.customer.CustomerId
import io.github.crabzilla.vertx.EntityCommandExecution
import io.github.crabzilla.vertx.configHandler
import io.github.crabzilla.vertx.deployVerticles
import io.github.crabzilla.vertx.deployVerticlesByName
import io.github.crabzilla.vertx.entity.CmdHandlerVerticleFactory
import io.github.crabzilla.vertx.helpers.EndpointsHelper.cmdHandlerEndpoint
import io.vertx.core.AbstractVerticle
import io.vertx.core.DeploymentOptions
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.eventbus.DeliveryOptions
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager
import joptsimple.OptionParser
import java.util.*

// tag::launcher[]

class HandlerServiceLauncher : AbstractVerticle() {

  companion object {

    val log = org.slf4j.LoggerFactory.getLogger(HandlerServiceLauncher::class.java.simpleName)

    lateinit var ds: HikariDataSource

    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {

      val parser = OptionParser()
      parser.accepts("conf").withRequiredArg()
      parser.allowsUnrecognizedOptions()

      val options = parser.parse(*args)
      val configFile = options.valueOf("conf") as String?

      val mgr = HazelcastClusterManager()
      val vertxOptions = VertxOptions().setClusterManager(mgr).setHAEnabled(true).setHAGroup("command-handler")

      println("**  HA group" + vertxOptions.haGroup)

      Vertx.clusteredVertx(vertxOptions) { res ->
        if (res.succeeded()) {

          val vertx = res.result()

          val defaultConfigFile = HandlerServiceLauncher::class.java.classLoader
            .getResource("conf/config.properties").path

          configHandler(vertx, configFile, defaultConfigFile, { config ->

            log.info("config = {}", config.encodePrettily())

            val app = DaggerHandlerServiceComponent.builder()
              .handlerServiceModule(HandlerServiceModule(vertx, config))
              .build()

            ds = app.datasource()

            vertx.registerVerticleFactory(CmdHandlerVerticleFactory(app.commandVerticles()))

            val workerDeploymentOptions = DeploymentOptions().setHa(true).setWorker(true)

            deployVerticles(vertx, setOf(HandlerServiceLauncher()))

            deployVerticlesByName(vertx, setOf("crabzilla-command-handler:example1"), workerDeploymentOptions)

            // justForTest(vertx)

          }, {
            ds.close()
          })

        } else {
          log.error("Error", res.cause())
        }
      }

    }

  }

}

// end::launcher[]

fun justForTest(vertx: Vertx) {

  val customerId = CustomerId(UUID.randomUUID().toString())
  //    val customerId = new CustomerId("customer123");
  val createCustomerCmd = CreateCustomer(UUID.randomUUID(), customerId, "a good customer")
  val options = DeliveryOptions().setCodecName("EntityCommand")

  // create customer command
  vertx.eventBus().send<EntityCommandExecution>(cmdHandlerEndpoint(Customer::class.java), createCustomerCmd, options) { asyncResult ->

    HandlerServiceLauncher.log.info("Successful create customer test? {}", asyncResult.succeeded())

    if (asyncResult.succeeded()) {

      HandlerServiceLauncher.log.info("Result: {}", asyncResult.result().body())

      val activateCustomerCmd = ActivateCustomer(UUID.randomUUID(), createCustomerCmd.targetId, "because I want it")

      // activate customer command
      vertx.eventBus().send<EntityCommandExecution>(cmdHandlerEndpoint(Customer::class.java), activateCustomerCmd, options) { asyncResult2 ->

        HandlerServiceLauncher.log.info("Successful activate customer test? {}", asyncResult2.succeeded())

        if (asyncResult2.succeeded()) {
          HandlerServiceLauncher.log.info("Result: {}", asyncResult2.result().body())
        } else {
          HandlerServiceLauncher.log.info("Cause: {}", asyncResult2.cause())
          HandlerServiceLauncher.log.info("Message: {}", asyncResult2.cause().message)
        }

      }

    } else {
      HandlerServiceLauncher.log.info("Cause: {}", asyncResult.cause())
      HandlerServiceLauncher.log.info("Message: {}", asyncResult.cause().message)
    }

  }

}