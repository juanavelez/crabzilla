package io.github.crabzilla.pgc.example1

import io.github.crabzilla.example1.customer.CustomerActivated
import io.github.crabzilla.example1.customer.CustomerCreated
import io.github.crabzilla.example1.customer.CustomerDeactivated
import io.github.crabzilla.framework.DomainEvent
import io.github.crabzilla.pgc.PgcEventProjector
import io.github.crabzilla.pgc.runPreparedQuery
import io.vertx.core.Promise
import io.vertx.sqlclient.Transaction
import io.vertx.sqlclient.Tuple
import org.slf4j.LoggerFactory

class BadEventProjector : PgcEventProjector {

  private val log = LoggerFactory.getLogger(BadEventProjector::class.java.name)

  override fun handle(pgTx: Transaction, targetId: Int, event: DomainEvent): Promise<Void> {

    log.info("event {} ", event)

    return when (event) {
      is CustomerCreated -> {
        val query = "INSERT INTO XXXXXX (id, name, is_active) VALUES ($1, $2, $3)"
        val tuple = Tuple.of(targetId, event.name, false)
        pgTx.runPreparedQuery(query, tuple)
      }
      is CustomerActivated -> {
        val query = "UPDATE XXX SET is_active = true WHERE id = $1"
        val tuple = Tuple.of(targetId)
        pgTx.runPreparedQuery(query, tuple)
      }
      is CustomerDeactivated -> {
        val query = "UPDATE XX SET is_active = false WHERE id = $1"
        val tuple = Tuple.of(targetId)
        pgTx.runPreparedQuery(query, tuple)
      }
      else -> {
        Promise.failedPromise("${event.javaClass.simpleName} does not have any event projector handler")
      }
    }

  }

}
