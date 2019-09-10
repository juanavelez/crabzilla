package io.github.crabzilla.pgc

import io.github.crabzilla.framework.*
import io.github.crabzilla.framework.UnitOfWork.JsonMetadata.EVENTS_JSON_CONTENT
import io.github.crabzilla.framework.UnitOfWork.JsonMetadata.EVENT_NAME
import io.github.crabzilla.internal.RangeOfEvents
import io.github.crabzilla.internal.UnitOfWorkEvents
import io.github.crabzilla.internal.UnitOfWorkRepository
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.pgclient.PgPool
import io.vertx.sqlclient.Tuple
import org.slf4j.LoggerFactory
import java.util.*

internal class PgcUowRepo<E: Entity>(private val pgPool: PgPool, private val jsonFunctions: EntityJsonAware<E>)
  : UnitOfWorkRepository {

  companion object {

    internal val log = LoggerFactory.getLogger(PgcUowRepo::class.java)

    private const val UOW_ID = "uow_id"
    private const val UOW_EVENTS = "uow_events"
    private const val CMD_ID = "cmd_id"
    private const val CMD_DATA = "cmd_data"
    private const val CMD_NAME = "cmd_name"
    private const val AR_ID = "ar_id"
    private const val AR_NAME = "ar_name"
    private const val VERSION = "version"

    private const val SQL_SELECT_FIELDS =
      "select uow_id, uow_events, cmd_id, cmd_data, cmd_name, ar_id, ar_name, version"
    const val SQL_SELECT_UOW_BY_CMD_ID = "$SQL_SELECT_FIELDS from units_of_work where cmd_id = $1"
    const val SQL_SELECT_UOW_BY_UOW_ID = "$SQL_SELECT_FIELDS from units_of_work where uow_id = $1"
    const val SQL_SELECT_UOW_BY_ENTITY_ID = "$SQL_SELECT_FIELDS from units_of_work where ar_id = $1 order by version"
    const val SQL_SELECT_AFTER_VERSION =  "select uow_events, version from units_of_work " +
      "where ar_id = $1 and ar_name = $2 and version > $3 order by version"

  }

  override fun getUowByCmdId(cmdId: UUID, aHandler: Handler<AsyncResult<Pair<UnitOfWork, Long>>>) {

    val params = Tuple.of(cmdId)

    pgPool.preparedQuery(SQL_SELECT_UOW_BY_CMD_ID, params) { ar ->
      if (ar.failed()) {
        aHandler.handle(Future.failedFuture(ar.cause()))
        return@preparedQuery
      }

      val rows = ar.result()
      if (rows.size() == 0) {
        aHandler.handle(Future.succeededFuture(null))
        return@preparedQuery
      }

      val row = rows.first()
      val commandName = row.getString(CMD_NAME)
      val commandAsJson = row.get(JsonObject::class.java, 3)
      val command = try { jsonFunctions.cmdFromJson(commandName, commandAsJson) }
                    catch (e: Exception) { null }

      if (command == null) {
        aHandler.handle(Future.failedFuture("error when getting command $commandName from json "))
        return@preparedQuery
      }

      val jsonArray = row.get(JsonArray::class.java, 1)

      val jsonToEventPair: (Int) -> Pair<String, DomainEvent> = { index ->
        val jsonObject = jsonArray.getJsonObject(index)
        val eventName = jsonObject.getString(EVENT_NAME)
        val eventJson = jsonObject.getJsonObject(EVENTS_JSON_CONTENT)
        val domainEvent = jsonFunctions.eventFromJson(eventName, eventJson)
        domainEvent
      }

      val events: List<Pair<String, DomainEvent>> = List(jsonArray.size(), jsonToEventPair)
      val uowId = row.getLong(UOW_ID)
      val uow = UnitOfWork(row.getString(AR_NAME), row.getInteger(AR_ID),
        row.getUUID(CMD_ID), row.getString(CMD_NAME), command, row.getInteger(VERSION)!!, events)
      aHandler.handle(Future.succeededFuture(Pair(uow, uowId)))
    }

  }

  override fun getUowByUowId(uowId: Long, aHandler: Handler<AsyncResult<UnitOfWork>>) {

    val params = Tuple.of(uowId)

    pgPool.preparedQuery(SQL_SELECT_UOW_BY_UOW_ID, params) { ar ->
      if (ar.failed()) {
        aHandler.handle(Future.failedFuture(ar.cause()))
        return@preparedQuery
      }

      val rows = ar.result()
      if (rows.size() == 0) {
        aHandler.handle(Future.succeededFuture(null))
        return@preparedQuery
      }

      val row = rows.first()
      val commandName = row.getString(CMD_NAME)
      val commandAsJson = row.get(JsonObject::class.java, 3)
      val command = try { jsonFunctions.cmdFromJson(commandName, commandAsJson) } catch (e: Exception) { null }

      if (command == null) {
        aHandler.handle(Future.failedFuture("error when getting command $commandName from json "))
        return@preparedQuery
      }

      val jsonArray = row.get(JsonArray::class.java, 1)

      val jsonToEventPair: (Int) -> Pair<String, DomainEvent> = { index ->
        val jsonObject = jsonArray.getJsonObject(index)
        val eventName = jsonObject.getString(EVENT_NAME)
        val eventJson = jsonObject.getJsonObject(EVENTS_JSON_CONTENT)
        val domainEvent = jsonFunctions.eventFromJson(eventName, eventJson)
        domainEvent
      }

      val events: List<Pair<String, DomainEvent>> = List(jsonArray.size(), jsonToEventPair)
      val uow = UnitOfWork(row.getString(AR_NAME), row.getInteger(AR_ID),
        row.getUUID(CMD_ID), row.getString(CMD_NAME), command, row.getInteger(VERSION)!!, events)
      aHandler.handle(Future.succeededFuture(uow))
    }
  }

  override fun getAllUowByEntityId(id: Int, aHandler: Handler<AsyncResult<List<UnitOfWork>>>) {

    val params = Tuple.of(id)

    pgPool.preparedQuery(SQL_SELECT_UOW_BY_ENTITY_ID, params) { ar ->
      if (ar.failed()) {
        aHandler.handle(Future.failedFuture(ar.cause()))
        return@preparedQuery
      }

      val result = ArrayList<UnitOfWork>()

      val rows = ar.result()

      for (row in rows) {

        val commandName = row.getString(CMD_NAME)
        val commandAsJson = row.get(JsonObject::class.java, 3)
        val command =
          try { jsonFunctions.cmdFromJson(commandName, commandAsJson) } catch (e: Exception) { null }

        if (command == null) {
          aHandler.handle(Future.failedFuture("error when getting command $commandName from json "))
          return@preparedQuery
        }

        val jsonArray = row.get(JsonArray::class.java, 1)

        val jsonToEventPair: (Int) -> Pair<String, DomainEvent> = { index ->
          val jsonObject = jsonArray.getJsonObject(index)
          val eventName = jsonObject.getString(EVENT_NAME)
          val eventJson = jsonObject.getJsonObject(EVENTS_JSON_CONTENT)
          val domainEvent = jsonFunctions.eventFromJson(eventName, eventJson)
          domainEvent
        }

        val events: List<Pair<String, DomainEvent>> = List(jsonArray.size(), jsonToEventPair)
        val uow = UnitOfWork(row.getString(AR_NAME), row.getInteger(AR_ID),
          row.getUUID(CMD_ID), row.getString(CMD_NAME), command, row.getInteger(VERSION)!!, events)

        result.add(uow)

      }

      aHandler.handle(Future.succeededFuture(result))
    }
  }

  override fun selectAfterVersion(id: Int, version: Version, aggregateRootName: String,
                                  aHandler: Handler<AsyncResult<RangeOfEvents>>) {

    log.trace("will load id [{}] after version [{}]", id, version)

    pgPool.getConnection { ar0 ->
      if (ar0.failed()) {
        aHandler.handle(Future.failedFuture(ar0.cause()));
        return@getConnection
      }
      val conn = ar0.result()
      conn.prepare(SQL_SELECT_AFTER_VERSION) { ar1 ->

        if (ar1.failed()) {
          aHandler.handle(Future.failedFuture(ar1.cause()))

        } else {
          val pq = ar1.result()
          // Fetch 100 rows at a time
          val tuple = Tuple.of( id, aggregateRootName, version)
          val stream = pq.createStream(100, tuple)
          val list = ArrayList<RangeOfEvents>()

          // Use the stream
          stream.handler { row ->
            val eventsArray = row.get(JsonArray::class.java, 0)
            val jsonToEventPair: (Int) -> Pair<String, DomainEvent> = { index ->
              val jsonObject = eventsArray.getJsonObject(index)
              val eventName = jsonObject.getString(EVENT_NAME)
              val eventJson = jsonObject.getJsonObject(EVENTS_JSON_CONTENT)
              jsonFunctions.eventFromJson(eventName, eventJson)
            }
            val events: List<Pair<String, DomainEvent>> = List(eventsArray.size(), jsonToEventPair)
            val snapshotData = RangeOfEvents(version, row.getInteger(1)!!, events)
            list.add(snapshotData)
          }

          stream.endHandler {
            log.trace("found {} units of work for id {} and version > {}", list.size, id, version)
            val finalVersion = if (list.size == 0) 0 else list[list.size - 1].untilVersion
            val flatMappedToEvents = list.flatMap { sd -> sd.events }
            aHandler.handle(Future.succeededFuture(RangeOfEvents(version, finalVersion, flatMappedToEvents)))
          }

          stream.exceptionHandler { err ->
            log.error(err.message); aHandler.handle(Future.failedFuture(err))
          }
        }
      }
    }
  }


  override fun selectAfterUowId(uowId: Long, maxRows: Int,
                                aHandler: Handler<AsyncResult<List<UnitOfWorkEvents>>>) {

    log.trace("will load after uowId [{}]", uowId)

    val selectAfterUowIdSql = "select uow_id, ar_id, uow_events " +
      "  from units_of_work " +
      " where uow_id > $1 " +
      " order by uow_id " +
      " limit " + maxRows

    val list = ArrayList<UnitOfWorkEvents>()

    pgPool.preparedQuery(selectAfterUowIdSql, Tuple.of(uowId)) { ar ->
      if (ar.failed()) {
        aHandler.handle(Future.failedFuture(ar.cause().message))
        return@preparedQuery
      }
      val rows = ar.result()
      if (rows.size() == 0) {
        aHandler.handle(Future.succeededFuture(list))
        return@preparedQuery
      }
      for (row in rows) {
        val uowSeq = row.getLong("uow_id")
        val targetId = row.getInteger("ar_id")
        val eventsArray = row.get(JsonArray::class.java, 2)
        val jsonToEventPair: (Int) -> Pair<String, DomainEvent> = { index ->
          val jsonObject = eventsArray.getJsonObject(index)
          val eventName = jsonObject.getString(EVENT_NAME)
          val eventJson = jsonObject.getJsonObject(EVENTS_JSON_CONTENT)
          jsonFunctions.eventFromJson(eventName, eventJson)
        }
        val events: List<Pair<String, DomainEvent>> = List(eventsArray.size(), jsonToEventPair)
        val projectionData = UnitOfWorkEvents(uowSeq.toLong(), targetId, events)
        list.add(projectionData)
      }
      aHandler.handle(Future.succeededFuture(list))
    }

  }

}