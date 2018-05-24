package io.github.crabzilla.vertx.modules.test

import dagger.Module
import io.github.crabzilla.vertx.modules.CrabzillaModule
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject

@Module
class TestModule(vertx: Vertx, config: JsonObject) : CrabzillaModule(vertx, config) {

}