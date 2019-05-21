package io.github.crabzilla

import io.vertx.core.AsyncResult
import io.vertx.core.Handler
import java.util.*

interface UnitOfWorkRepository {

  fun getUowByCmdId(cmdId: UUID, aHandler: Handler<AsyncResult<UnitOfWork>>)

  fun getUowByUowId(uowId: UUID, aHandler: Handler<AsyncResult<UnitOfWork>>)

  operator fun get(query: String, id: UUID, aHandler: Handler<AsyncResult<UnitOfWork>>)

  fun selectAfterVersion(id: Int, version: Version, aggregateRootName: String,
                         aHandler: Handler<AsyncResult<SnapshotEvents>>)

  fun selectAfterUowSequence(uowSequence: Int, maxRows: Int,
                             aHandler: Handler<AsyncResult<List<UnitOfWorkEvents>>>)

  fun getAllUowByEntityId(id: Int, aHandler: Handler<AsyncResult<List<UnitOfWork>>>)
}
