package io.github.crabzilla.web

import io.github.crabzilla.*
import io.github.crabzilla.web.ContentTypes.ENTITY_WRITE_MODEL
import io.vertx.core.Handler
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import org.slf4j.LoggerFactory

class WebEntityComponentImpl<E: Entity>(private val component: EntityComponent<E>, private val resourceName: String,
                                        private val router: Router)
  : WebEntityComponent {

  private val postCmd = "/$resourceName/:$ENTITY_ID_PARAMETER/commands/:$COMMAND_NAME_PARAMETER"
  private val getSnapshot = "/$resourceName/:$ENTITY_ID_PARAMETER"
  private val getAllUow = "/$resourceName/:$ENTITY_ID_PARAMETER/units-of-work"
  private val getUow = "/$resourceName/units-of-work/:unitOfWorkId"

  companion object {
    const val COMMAND_NAME_PARAMETER = "commandName"
    const val COMMAND_ID_PARAMETER = "commandId"
    const val ENTITY_ID_PARAMETER = "entityId"
    const val UNIT_OF_WORK_ID_PARAMETER = "unitOfWorkId"
    private val log = LoggerFactory.getLogger(WebEntityComponentImpl::class.java)

  }

  override fun deployWebRoutes() {

    log.info("adding route $postCmd")

    router.post(postCmd).handler {
      val begin = System.currentTimeMillis()
      val commandMetadata = CommandMetadata(it.pathParam(ENTITY_ID_PARAMETER).toInt(),
                                            it.pathParam(COMMAND_NAME_PARAMETER))
      val command: Command? = try { component.cmdFromJson(commandMetadata.commandName, it.bodyAsJson) }
                              catch (e: Exception) { null }
      if (command == null) {
        it.response().setStatusCode(400).setStatusMessage("Cannot decode the json for this Command").end()
        return@handler
      }
      component.handleCommand(commandMetadata, command, Handler { event ->
        val end = System.currentTimeMillis()
        if (log.isTraceEnabled) { log.trace("handled command in " + (end - begin) + " ms") }
        if (event.succeeded()) {
          with(event.result()) {
            val location = it.request().absoluteURI().split('/').subList(0, 3)
              .reduce { acc, s ->  acc.plus("/$s")} + "/$resourceName/units-of-work/$second"
            it.response()
              .putHeader("accept", "application/json")
              .putHeader("Location", location)
              .setStatusCode(303)
              .end()
          }
        } else {
          it.response().setStatusCode(400).setStatusMessage(event.cause().message).end()
        }
      })
    }.failureHandler(errorHandler(ENTITY_ID_PARAMETER))

    log.info("adding route $getSnapshot")

    router.get(getSnapshot).handler {
      val entityId = it.pathParam(ENTITY_ID_PARAMETER).toInt()
      val accept = it.request().getHeader("accept")
      if (ENTITY_WRITE_MODEL == accept) {
        val httpResp = it.response()
        component.getSnapshot(entityId, Handler { event ->
          if (event.failed() || event.result() == null) {
            httpResp.statusCode = if (event.result() == null) 404 else 500
            httpResp.end()
          } else {
            val snapshot = event.result()
            val snapshotJson = JsonObject()
              .put("state", component.toJson(snapshot.state))
              .put("version", snapshot.version)
            if (snapshot.version > 0) {
              httpResp.headers().add("Content-Type", "application/json")
              httpResp.end(snapshotJson.encode())
            } else {
              httpResp.setStatusCode(404).end("Entity not found")
            }
          }
        })
      } else {
        it.next()
      }
    }.failureHandler(errorHandler(ENTITY_ID_PARAMETER))

    log.info("adding route $getAllUow")

    router.get(getAllUow).handler {
      val entityId = it.pathParam(ENTITY_ID_PARAMETER).toInt()
      val httpResp = it.response()
      component.getAllUowByEntityId(entityId, Handler { event ->
        if (event.failed() || event.result() == null) {
          httpResp.statusCode = if (event.result() == null) 404 else 500
          httpResp.end()
        } else {
          val resultList = event.result()
          httpResp.setStatusCode(200).setChunked(true)
            .headers().add("Content-Type", "application/json")
          httpResp.end(JsonArray(resultList).encode())
        }
      })
    }.failureHandler(errorHandler(ENTITY_ID_PARAMETER))

    log.info("adding route $getUow")

    router.get(getUow).handler {
      val uowId = it.pathParam(UNIT_OF_WORK_ID_PARAMETER).toLong()
      val httpResp = it.response()
      component.getUowByUowId(uowId, Handler { uowResult ->
        if (uowResult.failed() || uowResult.result() == null) {
          httpResp.statusCode = if (uowResult.result() == null) 404 else 500
          httpResp.end()
        } else {
          httpResp.setStatusCode(200).setChunked(true)
            .putHeader("Content-Type", "application/json")
            .putHeader("uowId", uowId.toString())
            .end(JsonObject.mapFrom(uowResult.result()).encode())
        }
      })
    }.failureHandler(errorHandler(UNIT_OF_WORK_ID_PARAMETER))
  }

  private fun errorHandler(paramName: String) : Handler<RoutingContext> {
    return Handler {
      log.error(it.failure().message, it.failure())
      when (it.failure()) {
        is NumberFormatException -> it.response().setStatusCode(400).end("path param $paramName must be a number")
        else -> {
          it.failure().printStackTrace()
          it.response().setStatusCode(500).end("server error")
        }
      }
    }
  }

}