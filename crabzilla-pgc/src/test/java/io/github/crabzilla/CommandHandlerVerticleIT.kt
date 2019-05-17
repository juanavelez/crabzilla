package io.github.crabzilla

import io.github.crabzilla.example1.*
import io.github.crabzilla.example1.CustomerCommandEnum.CREATE
import io.github.crabzilla.example1.CustomerJson.CUSTOMER_CMD_FROM_JSON
import io.github.crabzilla.example1.CustomerJson.CUSTOMER_CMD_TO_JSON
import io.github.crabzilla.example1.CustomerJson.CUSTOMER_EVENT_FROM_JSON
import io.github.crabzilla.example1.CustomerJson.CUSTOMER_EVENT_TO_JSON
import io.github.crabzilla.example1.CustomerJson.CUSTOMER_FROM_JSON
import io.github.crabzilla.example1.CustomerJson.CUSTOMER_TO_JSON
import io.github.crabzilla.pgc.PgcSnapshotRepo
import io.github.crabzilla.pgc.PgcUowJournal
import io.reactiverse.pgclient.PgClient
import io.reactiverse.pgclient.PgPool
import io.reactiverse.pgclient.PgPoolOptions
import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.eventbus.DeliveryOptions
import io.vertx.core.eventbus.ReplyException
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith


@ExtendWith(VertxExtension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommandHandlerVerticleIT {

  // TODO https://slinkydeveloper.com/Assertions-With-Vertx-Futures-And-JUnit5/
  private lateinit var vertx: Vertx
  private lateinit var writeDb: PgPool
  private lateinit var verticle: CommandHandlerVerticle<Customer>

  private val options = DeliveryOptions()

  companion object {
    val handlerEndpoint = CommandHandlerEndpoint("customer")
  }

  @BeforeEach
  fun setup(tc: VertxTestContext) {

    vertx = Vertx.vertx()

    initVertx(vertx)

    val envOptions = ConfigStoreOptions()
      .setType("file")
      .setFormat("properties")
      .setConfig(JsonObject().put("path", "../example1.env"))

    val retriever = ConfigRetriever.create(vertx, ConfigRetrieverOptions().addStore(envOptions))

    retriever.getConfig(Handler { configFuture ->

      if (configFuture.failed()) {
        println("Failed to get configuration")
        tc.failNow(configFuture.cause())
        return@Handler
      }

      val config = configFuture.result()

      // println(config.encodePrettily())

      val options = PgPoolOptions()
        .setPort(5432)
        .setHost(config.getString("WRITE_DATABASE_HOST"))
        .setDatabase(config.getString("WRITE_DATABASE_NAME"))
        .setUser(config.getString("WRITE_DATABASE_USER"))
        .setPassword(config.getString("WRITE_DATABASE_PASSWORD"))
        .setMaxSize(config.getInteger("WRITE_DATABASE_POOL_MAX_SIZE"))

      writeDb = PgClient.pool(vertx, options)

      val journal = PgcUowJournal(writeDb, CUSTOMER_CMD_TO_JSON, CUSTOMER_EVENT_TO_JSON)

      val snapshotRepo = PgcSnapshotRepo(handlerEndpoint.entityName, writeDb, CUSTOMER_SEED_VALUE,
        CUSTOMER_STATE_BUILDER, CUSTOMER_FROM_JSON, CUSTOMER_TO_JSON, CUSTOMER_EVENT_FROM_JSON)

      verticle = CommandHandlerVerticle(handlerEndpoint, CUSTOMER_CMD_FROM_JSON, CUSTOMER_SEED_VALUE,
                        CUSTOMER_STATE_BUILDER, CUSTOMER_CMD_HANDLER_FACTORY, CUSTOMER_CMD_VALIDATOR, journal, snapshotRepo)

      writeDb.query("delete from units_of_work") { deleteResult1 ->
        if (deleteResult1.failed()) {
          deleteResult1.cause().printStackTrace()
          tc.failNow(deleteResult1.cause())
          return@query
        }
        writeDb.query("delete from customer_snapshots") { deleteResult2 ->
          if (deleteResult2.failed()) {
            deleteResult2.cause().printStackTrace()
            tc.failNow(deleteResult2.cause())
            return@query
          }
          vertx.deployVerticle(verticle) { event ->
            if (event.failed()) {
              tc.failNow(event.cause())
            }
            tc.completeNow()
          }
        }
      }

    })

  }

  @Test
  @DisplayName("given a valid command it will be SUCCESS")
  fun a1(tc: VertxTestContext) {

    val customerId = CustomerId(1)
    val createCustomerCmd = CreateCustomer("customer1")
    val commandMetadata = CommandMetadata(handlerEndpoint.entityName,
                                      customerId.value,
                                      CREATE.urlFriendly())
    val command = CUSTOMER_CMD_TO_JSON(createCustomerCmd)

    vertx.eventBus()
      .send<Pair<UnitOfWork, Int>>(handlerEndpoint.endpoint(), Pair(commandMetadata, command), options) { asyncResult ->
        if (asyncResult.failed()) {
          tc.failNow(asyncResult.cause())
          return@send
        }
        tc.verify {
          val result = asyncResult.result().body() as Pair<UnitOfWork, Int>
          tc.verify { assertThat(result.first.events.size).isEqualTo(1) }
          tc.completeNow()
        }
    }
  }

  @Test
  @DisplayName("given an invalid command it will be VALIDATION_ERROR")
  fun a2(tc: VertxTestContext) {

    val customerId = CustomerId(1)
    val createCustomerCmd = CreateCustomer("a bad name")
    val commandMetadata = CommandMetadata(handlerEndpoint.entityName,
      customerId.value,
        CREATE.urlFriendly())
    val command = CUSTOMER_CMD_TO_JSON(createCustomerCmd)

    vertx.eventBus()
      .send<Pair<UnitOfWork, Int>>(handlerEndpoint.endpoint(), Pair(commandMetadata, command), options) { asyncResult ->
        tc.verify {
          tc.verify { assertThat(asyncResult.succeeded()).isFalse() }
          val cause = asyncResult.cause() as ReplyException
          tc.verify { assertThat(cause.message).isEqualTo("[Invalid name: a bad name]") }
          tc.verify { assertThat(cause.failureCode()).isEqualTo(400) }
          tc.completeNow()
        }
    }

  }

  @Test
  @DisplayName("given an execution error it will be HANDLING_ERROR")
  fun a3(tc: VertxTestContext) {
    val customerId = CustomerId(1)
    val commandMetadata = CommandMetadata(handlerEndpoint.entityName, customerId.value, "unknown")
    val command = JsonObject()
    vertx.eventBus()
      .send<Pair<UnitOfWork, Int>>(handlerEndpoint.endpoint(), Pair(commandMetadata, command), options) { asyncResult ->
        tc.verify {
          tc.verify { assertThat(asyncResult.succeeded()).isFalse() }
          val cause = asyncResult.cause() as ReplyException
          tc.verify { assertThat(cause.message).isEqualTo("Command cannot be deserialized") }
          tc.verify { assertThat(cause.failureCode()).isEqualTo(400) }
          tc.completeNow()
        }
    }

  }

}
