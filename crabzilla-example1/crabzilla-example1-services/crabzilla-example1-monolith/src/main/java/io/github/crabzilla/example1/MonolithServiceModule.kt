package io.github.crabzilla.example1

import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet
import io.github.crabzilla.vertx.*
import io.github.crabzilla.vertx.handler.CommandHandlerService
import io.github.crabzilla.vertx.handler.impl.CommandHandlerServiceImpl
import io.github.crabzilla.vertx.modules.ProjectionDbModule
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.healthchecks.HealthCheckHandler

@Module(includes = [Example1HandlerModule::class, Example1ProjectorModule::class, ProjectionDbModule::class, CrabzillaWebModule::class])
class MonolithServiceModule(vertx: Vertx, config: JsonObject) : CrabzillaModule(vertx, config) {

  @Provides
  fun handlerService() : CommandHandlerService {
    return CommandHandlerServiceImpl(vertx, subDomainName())
  }

  @Provides @IntoSet
  fun restVerticle(uowRepository: UnitOfWorkRepository, config: JsonObject,
                   handlerService: CommandHandlerService,
                   @WebHealthCheck healthCheckHandler: HealthCheckHandler): CommandRestVerticle {
    return CommandRestVerticle(subDomainName(), config, healthCheckHandler, uowRepository, handlerService)
  }

}