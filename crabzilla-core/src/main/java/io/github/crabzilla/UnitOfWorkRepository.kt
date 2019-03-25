package io.github.crabzilla

import io.vertx.core.Future
import java.util.*

interface UnitOfWorkRepository {

  fun getUowByCmdId(cmdId: UUID, future: Future<UnitOfWork>)

  fun getUowByUowId(uowId: UUID, future: Future<UnitOfWork>)

  operator fun get(query: String, id: UUID, future: Future<UnitOfWork>)

  fun selectAfterVersion(id: Int, version: Version, future: Future<SnapshotData>, aggregateRootName: String)

  fun append(unitOfWork: UnitOfWork, future: Future<Int>, aggregateRootName: String)

  fun selectAfterUowSequence(uowSequence: Int, maxRows: Int, future: Future<List<ProjectionData>>)

}