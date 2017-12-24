package io.github.crabzilla.example1

import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import io.github.crabzilla.example1.services.SampleInternalServiceImpl
import io.github.crabzilla.vertx.projection.EventProjector
import io.github.crabzilla.vertx.projection.EventsProjectionVerticle
import io.vertx.circuitbreaker.CircuitBreaker
import io.vertx.circuitbreaker.CircuitBreakerOptions
import io.vertx.core.Verticle
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import org.jdbi.v3.core.Jdbi
import javax.inject.Singleton

@Module
class Example1Module(val vertx: Vertx, val config: JsonObject) {

  @Provides
  @Singleton
  fun vertx(): Vertx {
    return vertx
  }

  @Provides
  @Singleton
  fun config(): JsonObject {
    return config
  }

  @Provides
  @Singleton
  fun customerRepository(dao: CustomerSummaryDao): CustomerRepository {
    return CustomerRepositoryImpl(dao)
  }

  @Provides
  @Singleton
  fun service(): SampleInternalService {
    return SampleInternalServiceImpl()
  }

  @Provides
  @Singleton
  fun customerSummaryDao(jdbi: Jdbi) : CustomerSummaryDao {
    return jdbi.onDemand(CustomerSummaryDao::class.java)
  }

  @Provides @IntoMap
  @StringKey("EventsProjectionVerticle")
  fun eventsProjectorVerticle(vertx: Vertx, eventsProjector: EventProjector<CustomerSummaryDao>): Verticle {
    val circuitBreaker = CircuitBreaker.create("events-projection-circuit-breaker", vertx)
    return EventsProjectionVerticle(eventsProjector, circuitBreaker)
  }

  @Provides
  @Singleton
  fun eventsProjector(jdbi: Jdbi): EventProjector<CustomerSummaryDao> {
    return Example1EventProjector("example1", CustomerSummaryDao::class.java, jdbi)
  }

}