package io.github.crabzilla.pgc

import io.github.crabzilla.*
import io.github.crabzilla.example1.CustomerCommandEnum.ACTIVATE
import io.github.crabzilla.example1.CustomerCommandEnum.CREATE
import io.github.crabzilla.pgc.PgcUowJournal.Companion.SQL_APPEND_UOW
import io.github.crabzilla.pgc.example1.Example1Fixture.activateCmd1
import io.github.crabzilla.pgc.example1.Example1Fixture.activated1
import io.github.crabzilla.pgc.example1.Example1Fixture.activatedUow1
import io.github.crabzilla.pgc.example1.Example1Fixture.createCmd1
import io.github.crabzilla.pgc.example1.Example1Fixture.created1
import io.github.crabzilla.pgc.example1.Example1Fixture.createdUow1
import io.github.crabzilla.pgc.example1.Example1Fixture.customerEntityName
import io.github.crabzilla.pgc.example1.Example1Fixture.customerId1
import io.github.crabzilla.pgc.example1.Example1Fixture.customerJson
import io.reactiverse.pgclient.PgClient
import io.reactiverse.pgclient.PgPool
import io.reactiverse.pgclient.PgPoolOptions
import io.reactiverse.pgclient.Tuple
import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigInteger


@ExtendWith(VertxExtension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgcUowRepoIT {

  private lateinit var vertx: Vertx
  private lateinit var writeDb: PgPool
  private lateinit var repo: UnitOfWorkRepository
  private lateinit var journal: UnitOfWorkJournal

  @BeforeEach
  fun setup(tc: VertxTestContext) {

    vertx = Vertx.vertx()

    Crabzilla.initVertx(vertx)

    val envOptions = ConfigStoreOptions()
      .setType("file")
      .setFormat("properties")
      .setConfig(JsonObject().put("path", "../example1.env"))

    val options = ConfigRetrieverOptions().addStore(envOptions)

    val retriever = ConfigRetriever.create(vertx, options)

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
      repo = PgcUowRepo(writeDb, customerJson)
      journal = PgcUowJournal(writeDb, customerJson)

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
          tc.completeNow()
        }
      }


    })

  }

  @Test
  @DisplayName("can queries an unit of work row by it's command id")
  fun a4(tc: VertxTestContext) {

    val tuple = Tuple.of(
      io.reactiverse.pgclient.data.Json.create(customerJson.toJsonArray(arrayListOf(Pair("CustomerCreated", created1)))),
      createdUow1.commandId,
      CREATE.urlFriendly(),
      io.reactiverse.pgclient.data.Json.create(customerJson.cmdToJson(createCmd1)),
      customerEntityName,
      customerId1.value,
      1)

    writeDb.preparedQuery(SQL_APPEND_UOW, tuple) { ar1 ->
      if (ar1.failed()) {
        ar1.cause().printStackTrace()
        tc.failNow(ar1.cause())
      }
      val uowSequence = ar1.result().first().getLong(0)
      tc.verify { tc.verify { assertThat(uowSequence).isGreaterThan(0) } }
      val selectFuture = Future.future<UnitOfWork>()
      selectFuture.setHandler { ar2 ->
        if (ar2.failed()) {
          tc.failNow(ar2.cause())
        }
        val uow = ar2.result()
        tc.verify { tc.verify { assertThat(createdUow1).isEqualTo(uow) } }
        tc.completeNow()
      }
      repo.getUowByCmdId(createdUow1.commandId, selectFuture)
    }

  }

  @Test
  @DisplayName("can queries an unit of work row by it's uow id")
  fun a5(tc: VertxTestContext) {

    val tuple = Tuple.of(
      io.reactiverse.pgclient.data.Json.create(customerJson.toJsonArray(arrayListOf(Pair("CustomerCreated", created1)))),
      createdUow1.commandId,
      CREATE.urlFriendly(),
      io.reactiverse.pgclient.data.Json.create(customerJson.cmdToJson(createCmd1)),
      customerEntityName,
      customerId1.value,
      1)

    writeDb.preparedQuery(SQL_APPEND_UOW, tuple) { ar1 ->
      if (ar1.failed()) {
        tc.failNow(ar1.cause())
      }
      val uowSequence = ar1.result().first().getNumeric("uow_id").bigIntegerValue()
      tc.verify { assertThat(uowSequence).isGreaterThan(BigInteger.ZERO) }
      repo.getUowByUowId(uowSequence, Handler { ar2 ->
        if (ar2.failed()) {
          tc.failNow(ar2.cause())
        }
        val uow = ar2.result()
        tc.verify { assertThat(createdUow1).isEqualTo(uow) }
        tc.completeNow()
      })
    }

  }

  @Nested
  @DisplayName("When selecting by uow sequence")
  @ExtendWith(VertxExtension::class)
  inner class WhenSelectingByUowSeq {

    @Test
    @DisplayName("can queries an empty repo")
    fun a1(tc: VertxTestContext) {
      val selectFuture = Future.future<List<UnitOfWorkEvents>>()
      repo.selectAfterUowId(BigInteger.ONE, 100, selectFuture)
      selectFuture.setHandler { selectAsyncResult ->
        val snapshotData = selectAsyncResult.result()
        tc.verify { assertThat(snapshotData.size).isEqualTo(0) }
        tc.completeNow()
      }
    }

    @Test
    @DisplayName("can queries a single unit of work row")
    fun a2(tc: VertxTestContext) {

      val tuple = Tuple.of(
        io.reactiverse.pgclient.data.Json.create(customerJson.toJsonArray(arrayListOf(Pair("CustomerCreated", created1)))),
        createdUow1.commandId,
        CREATE.urlFriendly(),
        io.reactiverse.pgclient.data.Json.create(customerJson.cmdToJson(createCmd1)),
        customerEntityName,
        customerId1.value,
        1)

      val selectFuture = Future.future<List<UnitOfWorkEvents>>()

      writeDb.preparedQuery(SQL_APPEND_UOW, tuple) { ar ->
        if (ar.failed()) {
          ar.cause().printStackTrace()
          tc.failNow(ar.cause())
        }
        val uowSequence = ar.result().first().getLong(0)
        tc.verify { assertThat(uowSequence).isGreaterThan(0) }
        repo.selectAfterUowId(BigInteger.ZERO, 100, selectFuture)
        selectFuture.setHandler { selectAsyncResult ->
          val snapshotData = selectAsyncResult.result()
          tc.verify { assertThat(snapshotData.size).isEqualTo(1) }
          val (uowSequence, targetId, events) = snapshotData[0]
          tc.verify { assertThat(uowSequence).isGreaterThan(BigInteger.ZERO) }
          tc.verify { assertThat(targetId).isEqualTo(customerId1.value) }
          tc.verify { assertThat(events).isEqualTo(arrayListOf(Pair("CustomerCreated", created1))) }
          tc.completeNow()
        }
      }
    }

    @Test
    @DisplayName("can queries two unit of work rows")
    fun a3(tc: VertxTestContext) {

      val tuple1 = Tuple.of(
        io.reactiverse.pgclient.data.Json.create(customerJson.toJsonArray(arrayListOf(Pair("CustomerCreated", created1)))),
        createdUow1.commandId,
        CREATE.urlFriendly(),
        io.reactiverse.pgclient.data.Json.create(customerJson.cmdToJson(createCmd1)),
        customerEntityName,
        customerId1.value,
        1)

      val selectFuture1 = Future.future<List<UnitOfWorkEvents>>()

      writeDb.preparedQuery(SQL_APPEND_UOW, tuple1) { ar1 ->

        if (ar1.failed()) {
          ar1.cause().printStackTrace()
          tc.failNow(ar1.cause())
        }

        val tuple2 = Tuple.of(
          io.reactiverse.pgclient.data.Json.create(customerJson.toJsonArray(arrayListOf(Pair("CustomerActivated", activated1)))),
          activatedUow1.commandId,
          ACTIVATE.urlFriendly(),
          io.reactiverse.pgclient.data.Json.create(customerJson.cmdToJson(activateCmd1)),
          customerEntityName,
          customerId1.value,
          2)

        writeDb.preparedQuery(SQL_APPEND_UOW, tuple2) { ar2 ->
          if (ar2.failed()) {
            ar2.cause().printStackTrace()
            tc.failNow(ar2.cause())
          }
          repo.selectAfterUowId(BigInteger.ZERO, 100, selectFuture1)
          selectFuture1.setHandler { selectAsyncResult ->
            val snapshotData = selectAsyncResult.result()
            tc.verify { assertThat(snapshotData.size).isEqualTo(2) }
            val (uowSequence1, targetId1, events1) = snapshotData[0]
            tc.verify { assertThat(uowSequence1).isGreaterThan(BigInteger.ZERO) }
            tc.verify { assertThat(targetId1).isEqualTo(customerId1.value) }
            tc.verify { assertThat(events1).isEqualTo(arrayListOf(Pair("CustomerCreated", created1))) }
            val (uowSequence2, targetId2, events2) = snapshotData[1]
            tc.verify { assertThat(uowSequence2).isEqualTo(uowSequence1.inc()) }
            tc.verify { assertThat(targetId2).isEqualTo(customerId1.value) }
            tc.verify { assertThat(events2).isEqualTo(arrayListOf(Pair("CustomerActivated", activated1))) }
            tc.completeNow()
          }
        }

      }
    }

    @Test
    @DisplayName("can query units of work for a given entity ID")
    fun a33(tc: VertxTestContext) {

      val tuple1 = Tuple.of(
        io.reactiverse.pgclient.data.Json.create(customerJson.toJsonArray(arrayListOf(Pair("CustomerCreated", created1)))),
        createdUow1.commandId,
        CREATE.urlFriendly(),
        io.reactiverse.pgclient.data.Json.create(customerJson.cmdToJson(createCmd1)),
        customerEntityName,
        customerId1.value,
        1)

      val selectFuture1 = Future.future<List<UnitOfWork>>()

      writeDb.preparedQuery(SQL_APPEND_UOW, tuple1) { ar1 ->

        if (ar1.failed()) {
          ar1.cause().printStackTrace()
          tc.failNow(ar1.cause())
        }

        val tuple2 = Tuple.of(
          io.reactiverse.pgclient.data.Json.create(customerJson.toJsonArray(arrayListOf(Pair("CustomerActivated", activated1)))),
          activatedUow1.commandId,
          ACTIVATE.urlFriendly(),
          io.reactiverse.pgclient.data.Json.create(customerJson.cmdToJson(activateCmd1)),
          customerEntityName,
          customerId1.value,
          2)

        writeDb.preparedQuery(SQL_APPEND_UOW, tuple2) { ar2 ->
          if (ar2.failed()) {
            ar2.cause().printStackTrace()
            tc.failNow(ar2.cause())
          }
          repo.getAllUowByEntityId(customerId1.value, selectFuture1)

          selectFuture1.setHandler { selectAsyncResult ->
            val uowList = selectAsyncResult.result()
            println(uowList)
            tc.verify { assertThat(uowList.size).isEqualTo(2) }
            val uow1 = uowList[0]
            val uow2 = uowList[1]
            tc.verify { assertThat(uow1).isEqualTo(createdUow1) }
            tc.verify { assertThat(uow2).isEqualTo(activatedUow1) }
            tc.completeNow()
          }
        }

      }
    }

    @Test
    @DisplayName("can queries by uow sequence")
    fun a4(tc: VertxTestContext) {

      val tuple1 = Tuple.of(
        io.reactiverse.pgclient.data.Json.create(customerJson.toJsonArray(arrayListOf(Pair("CustomerCreated", created1)))),
        createdUow1.commandId,
        CREATE.urlFriendly(),
        io.reactiverse.pgclient.data.Json.create(customerJson.cmdToJson(createCmd1)),
        customerEntityName,
        customerId1.value,
        1)

      val selectFuture1 = Future.future<List<UnitOfWorkEvents>>()

      writeDb.preparedQuery(SQL_APPEND_UOW, tuple1) { ar1 ->
        if (ar1.failed()) {
          ar1.cause().printStackTrace()
          tc.failNow(ar1.cause())
        }
        val uowSequence1 = ar1.result().first().getNumeric("uow_id").bigIntegerValue()
        val tuple2 = Tuple.of(
          io.reactiverse.pgclient.data.Json.create(customerJson.toJsonArray(arrayListOf(Pair("CustomerActivated", activated1)))),
          activatedUow1.commandId,
          ACTIVATE.urlFriendly(),
          io.reactiverse.pgclient.data.Json.create(customerJson.cmdToJson(activateCmd1)),
          customerEntityName,
          customerId1.value,
          2)
        writeDb.preparedQuery(SQL_APPEND_UOW, tuple2) { ar2 ->
          if (ar2.failed()) {
            ar2.cause().printStackTrace()
            tc.failNow(ar2.cause())
          }
          val uowSequence2 = ar2.result().first().getInteger("uow_id")
          repo.selectAfterUowId(uowSequence1, 100, selectFuture1)
          selectFuture1.setHandler { selectAsyncResult ->
            val snapshotData = selectAsyncResult.result()
            tc.verify { assertThat(snapshotData.size).isEqualTo(1) }
            val (uowSequence, targetId, events) = snapshotData[0]
            tc.verify { assertThat(uowSequence).isEqualTo(uowSequence2) }
            tc.verify { assertThat(targetId).isEqualTo(customerId1.value) }
            tc.verify { assertThat(events).isEqualTo(arrayListOf(Pair("CustomerActivated", activated1))) }
            tc.completeNow()
          }
        }
      }
    }
  }

  @Nested
  @DisplayName("When selecting by version")
  @ExtendWith(VertxExtension::class)
  inner class WhenSelectingByVersion {

    @Test
    @DisplayName("can queries a single unit of work row")
    fun a2(tc: VertxTestContext) {

      val tuple = Tuple.of(
        io.reactiverse.pgclient.data.Json.create(customerJson.toJsonArray(arrayListOf(Pair("CustomerCreated", created1)))),
        createdUow1.commandId,
        CREATE.urlFriendly(),
        io.reactiverse.pgclient.data.Json.create(customerJson.cmdToJson(createCmd1)),
        customerEntityName,
        customerId1.value,
        1)

      writeDb.preparedQuery(SQL_APPEND_UOW, tuple) { ar ->
        if (ar.failed()) {
          ar.cause().printStackTrace()
          tc.failNow(ar.cause())
        }
        val uowSequence = ar.result().first().getLong(0)
        tc.verify { assertThat(uowSequence).isGreaterThan(0) }
        val selectFuture = Future.future<RangeOfEvents>()
        repo.selectAfterVersion(customerId1.value, 0, customerEntityName, selectFuture.completer())
        selectFuture.setHandler { selectAsyncResult ->
          val snapshotData = selectAsyncResult.result()
          tc.verify { assertThat(1).isEqualTo(snapshotData.untilVersion) }
          tc.verify { assertThat(arrayListOf(Pair("CustomerCreated", created1))).isEqualTo(snapshotData.events) }
          tc.completeNow()
        }
      }

    }

    @Test
    @DisplayName("can queries two unit of work rows")
    fun a3(tc: VertxTestContext) {

      val tuple1 = Tuple.of(
        io.reactiverse.pgclient.data.Json.create(customerJson.toJsonArray(arrayListOf(Pair("CustomerCreated", created1)))),
        createdUow1.commandId,
        CREATE.urlFriendly(),
        io.reactiverse.pgclient.data.Json.create(customerJson.cmdToJson(createCmd1)),
        customerEntityName,
        customerId1.value,
        1)

      writeDb.preparedQuery(SQL_APPEND_UOW, tuple1) { ar1 ->

        if (ar1.failed()) {
          ar1.cause().printStackTrace()
          tc.failNow(ar1.cause())
        }

        val tuple2 = Tuple.of(
          io.reactiverse.pgclient.data.Json.create(customerJson.toJsonArray(arrayListOf(Pair("CustomerActivated", activated1)))),
          activatedUow1.commandId,
          ACTIVATE.urlFriendly(),
          io.reactiverse.pgclient.data.Json.create(customerJson.cmdToJson(activateCmd1)),
          customerEntityName,
          customerId1.value,
          2)

        writeDb.preparedQuery(SQL_APPEND_UOW, tuple2) { ar2 ->
          if (ar2.failed()) {
            ar2.cause().printStackTrace()
            tc.failNow(ar2.cause())
          }
          val selectFuture1 = Future.future<RangeOfEvents>()
          repo.selectAfterVersion(customerId1.value, 0, customerEntityName, selectFuture1)
          selectFuture1.setHandler { selectAsyncResult ->
            val snapshotData = selectAsyncResult.result()
            tc.verify { assertThat(2).isEqualTo(snapshotData.untilVersion) }
            tc.verify { assertThat(listOf(Pair("CustomerCreated", created1), Pair("CustomerActivated", activated1)))
              .isEqualTo(snapshotData.events) }
            tc.completeNow()
          }
        }
      }

    }

    @Test
    @DisplayName("can queries by version")
    fun a4(tc: VertxTestContext) {

      val tuple1 = Tuple.of(
        io.reactiverse.pgclient.data.Json.create(customerJson.toJsonArray(arrayListOf(Pair("CustomerCreated", created1)))),
        createdUow1.commandId,
        CREATE.urlFriendly(),
        io.reactiverse.pgclient.data.Json.create(customerJson.cmdToJson(createCmd1)),
        customerEntityName,
        customerId1.value,
        1)

      writeDb.preparedQuery(SQL_APPEND_UOW, tuple1) { ar1 ->
        if (ar1.failed()) {
          ar1.cause().printStackTrace()
          tc.failNow(ar1.cause())
          return@preparedQuery
        }
        val uowSequence1 = ar1.result().first().getLong("uow_id")
        val tuple2 = Tuple.of(
          io.reactiverse.pgclient.data.Json.create(customerJson.toJsonArray(arrayListOf(Pair("CustomerActivated", activated1)))),
          activatedUow1.commandId,
          ACTIVATE.urlFriendly(),
          io.reactiverse.pgclient.data.Json.create(customerJson.cmdToJson(activateCmd1)),
          customerEntityName,
          customerId1.value,
          2)
        writeDb.preparedQuery(SQL_APPEND_UOW, tuple2) { ar2 ->
          if (ar2.failed()) {
            ar2.cause().printStackTrace()
            tc.failNow(ar2.cause())
            return@preparedQuery
          }
          val uowSequence2 = ar2.result().first().getLong("uow_id")
          val selectFuture1 = Future.future<RangeOfEvents>()
          repo.selectAfterVersion(customerId1.value, 1, customerEntityName, selectFuture1.completer())
          selectFuture1.setHandler { selectAsyncResult ->
            val snapshotData = selectAsyncResult.result()
            tc.verify { assertThat(2).isEqualTo(snapshotData.untilVersion) }
            tc.verify { assertThat(arrayListOf(Pair("CustomerActivated", activated1))).isEqualTo(snapshotData.events) }
            tc.completeNow()
          }
        }
      }
    }
  }

  @Test
  @DisplayName("can queries only above version 1")
  fun s4(tc: VertxTestContext) {

    val appendFuture1 = Future.future<BigInteger>()

    // append uow1
    journal.append(createdUow1, appendFuture1)

    appendFuture1.setHandler { ar1 ->
      if (ar1.failed()) {
        ar1.cause().printStackTrace()
        tc.failNow(ar1.cause())
        return@setHandler
      }
      val uowSequence = ar1.result()
      tc.verify { assertThat(uowSequence).isGreaterThan(BigInteger.ZERO) }
      val appendFuture2 = Future.future<BigInteger>()
      // append uow2
      journal.append(activatedUow1, appendFuture2)
      appendFuture2.setHandler { ar2 ->
        if (ar2.failed()) {
          ar2.cause().printStackTrace()
          tc.failNow(ar2.cause())
          return@setHandler
        }
        val uowSequence = ar2.result()
        tc.verify { assertThat(uowSequence).isGreaterThan(BigInteger.valueOf(2)) }
        val snapshotDataFuture = Future.future<RangeOfEvents>()
        // get only above version 1
        repo.selectAfterVersion(activatedUow1.entityId, 1, customerEntityName, snapshotDataFuture)
        snapshotDataFuture.setHandler { ar4 ->
          if (ar4.failed()) {
            ar4.cause().printStackTrace()
            tc.failNow(ar4.cause())
            return@setHandler
          }
          val (afterVersion, untilVersion, events) = ar4.result()

          tc.verify { assertThat(afterVersion).isEqualTo(1) }
          tc.verify { assertThat(untilVersion).isEqualTo(activatedUow1.version) }
          tc.verify { assertThat(events).isEqualTo(arrayListOf(Pair("CustomerActivated", activated1))) }
          tc.completeNow()
        }
      }
    }

  }

}
