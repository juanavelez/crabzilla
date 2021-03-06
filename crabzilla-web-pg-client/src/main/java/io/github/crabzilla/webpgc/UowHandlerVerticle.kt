package io.github.crabzilla.webpgc

import io.github.crabzilla.framework.Entity
import io.github.crabzilla.framework.EntityJsonAware
import io.vertx.core.AbstractVerticle
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory

abstract class UowHandlerVerticle : AbstractVerticle() {

  companion object {
    val log: Logger = LoggerFactory.getLogger(UowHandlerVerticle::class.java)
    val processId: String = ManagementFactory.getRuntimeMXBean().name // TODO does this work with AOT?
  }

  private val jsonFunctions: MutableMap<String, EntityJsonAware<out Entity>> = mutableMapOf()

  override fun start() {
    val implClazz = this::class.java.name
    vertx.eventBus().consumer<String>(implClazz) { msg ->
      if (log.isTraceEnabled) log.trace("received " + msg.body())
      msg.reply("$implClazz is already running here: $processId")
    }
  }

  fun addEntityJsonAware(entityName: String, jsonAware: EntityJsonAware<out Entity>) {
    jsonFunctions[entityName] = jsonAware
  }

}
