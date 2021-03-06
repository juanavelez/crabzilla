package io.github.crabzilla.pgc

import io.github.crabzilla.framework.*
import io.github.crabzilla.framework.UnitOfWork.JsonMetadata.EVENTS_JSON_CONTENT
import io.github.crabzilla.framework.UnitOfWork.JsonMetadata.EVENT_NAME
import io.github.crabzilla.internal.SnapshotRepository
import io.vertx.core.Promise
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import io.vertx.pgclient.PgPool
import io.vertx.sqlclient.Transaction
import io.vertx.sqlclient.Tuple

class PgcSnapshotRepo<E : Entity>(private val writeModelDb: PgPool,
                                                                private val name: String,
                                                                private val entityFn: EntityCommandAware<E>,
                                                                private val eJsonFn: EntityJsonAware<E>) : SnapshotRepository<E> {

  companion object {
    internal val log = LoggerFactory.getLogger(PgcSnapshotRepo::class.java)
    const val SELECT_EVENTS_VERSION_AFTER_VERSION = "SELECT uow_events, version FROM units_of_work " +
      "WHERE ar_id = $1 and ar_name = $2 and version > $3 ORDER BY version "
  }

  private fun selectSnapshot(): String {
    return "SELECT version, json_content FROM ${name}_snapshots WHERE ar_id = $1"
  }

  private fun upsertSnapshot(): String {
    return "INSERT INTO ${name}_snapshots (ar_id, version, json_content) " +
      " VALUES ($1, $2, $3) " +
      " ON CONFLICT (ar_id) DO UPDATE SET version = $2, json_content = $3"
  }

  override fun upsert(entityId: Int, snapshot: Snapshot<E>): Promise<Void> {
    val promise = Promise.promise<Void>()
    val json = eJsonFn.toJson(snapshot.state)
    writeModelDb.preparedQuery(upsertSnapshot(), Tuple.of(entityId, snapshot.version, json)) { insert ->
      if (insert.failed()) {
        log.error("upsert snapshot query error")
        promise.fail(insert.cause())
      } else {
        log.trace("upsert snapshot success")
        promise.complete()
      }
    }
    return promise
  }

  override fun retrieve(entityId: Int): Promise<Snapshot<E>> {

    val promise = Promise.promise<Snapshot<E>>()

    writeModelDb.getConnection { res ->

      if (!res.succeeded()) {
        promise.fail(res.cause())
        return@getConnection

      } else {

        // Transaction must use a connection
        val conn = res.result()

        // TODO how to specify transaction isolation level?
        // Begin the transaction
        val tx: Transaction = conn.begin().abortHandler {
            log.error("Transaction aborted")
            promise.fail("Transaction aborted")
        }

        // get current snapshot
        conn.preparedQuery(selectSnapshot(), Tuple.of(entityId)) { event1 ->

          if (event1.failed()) {
            conn.close()
            promise.fail(event1.cause())
            return@preparedQuery

          } else {
            val pgRow = event1.result()

            val cachedInstance : E
            val cachedVersion : Int

            if (pgRow == null || pgRow.size() == 0) {
              cachedInstance = entityFn.initialState
              cachedVersion = 0
            } else {
              cachedInstance = eJsonFn.fromJson(pgRow.first().get(JsonObject::class.java, 1))
              cachedVersion = pgRow.first().getInteger("version")
            }

            // get committed events after snapshot version
            conn.prepare(SELECT_EVENTS_VERSION_AFTER_VERSION) { event2 ->

              if (!event2.succeeded()) {
                conn.close()
                promise.fail(event2.cause())
                return@prepare

              } else {
                var currentInstance = cachedInstance
                var currentVersion = cachedVersion

                val pq = event2.result()

                // Fetch 100 rows at a time
                val stream = pq.createStream(100, Tuple.of(entityId, name, cachedVersion))

                stream.exceptionHandler { err -> log.error("Retrieve: ${err.message}", err)
                  tx.rollback(); conn.close(); promise.fail(err)
                }

                stream.endHandler {
                  log.trace("End of stream")
                  // Attempt to commit the transaction
                  tx.commit { ar ->
                    // Return the connection to the pool
                    conn.close()
                    // But transaction abortion fails it
                    if (ar.failed()) {
                      log.error("endHandler.closeConnection")
                      promise.fail(ar.cause())
                    } else {
                      log.trace("success: endHandler.closeConnection")
                      val result = Snapshot(currentInstance, currentVersion)
                      promise.complete(result)
                    }
                  }
                }

                stream.handler { row ->
                  currentVersion = row.getInteger(1)
                  val eventsArray = row.get(JsonArray::class.java, 0)
                  val jsonToEvent: (Int) -> Pair<String, DomainEvent> = { index ->
                    val jsonObject = eventsArray.getJsonObject(index)
                    val eventName = jsonObject.getString(EVENT_NAME)
                    val eventJson = jsonObject.getJsonObject(EVENTS_JSON_CONTENT)
                    eJsonFn.eventFromJson(eventName, eventJson)
                  }

                  val events: List<Pair<String, DomainEvent>> = List(eventsArray.size(), jsonToEvent)
                  currentInstance =
                    events.fold(currentInstance) {state, event -> entityFn.applyEvent.invoke(event.second, state)}
                  log.trace("Events: $events \n version: $currentVersion \n instance $currentInstance")

                }
              }
            }
          }
        }
      }
    }

    return promise

  }
}
