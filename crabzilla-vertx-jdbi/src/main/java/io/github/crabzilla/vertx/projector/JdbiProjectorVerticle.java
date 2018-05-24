package io.github.crabzilla.vertx.projector;

import io.github.crabzilla.vertx.CrabzillaVerticle;
import io.github.crabzilla.vertx.ProjectionData;
import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.Message;
import org.slf4j.Logger;

import static io.github.crabzilla.vertx.VerticleRole.PROJECTOR;
import static java.util.Collections.singletonList;
import static org.slf4j.LoggerFactory.getLogger;

public class JdbiProjectorVerticle<DAO> extends CrabzillaVerticle {

  private static Logger log = getLogger(JdbiProjectorVerticle.class);

  private final String eventsSourceEndpoint;
  private final EventsProjector<DAO> eventsProjector;
  private final CircuitBreaker circuitBreaker;

  public JdbiProjectorVerticle(String eventsSourceEndpoint, EventsProjector<DAO> eventsProjector,
                               CircuitBreaker circuitBreaker) {
    super(eventsSourceEndpoint, PROJECTOR);
    this.eventsSourceEndpoint = eventsSourceEndpoint;
    this.eventsProjector = eventsProjector;
    this.circuitBreaker = circuitBreaker;
  }

  @Override
  public void start() {
    log.info("starting consuming from {}", eventsSourceEndpoint);
    vertx.eventBus().consumer(eventsSourceEndpoint, msgHandler());
  }

  Handler<Message<ProjectionData>> msgHandler() {
    return (Message<ProjectionData> msg) -> vertx.executeBlocking((Future<String> future) -> {
      final ProjectionData projectionData = msg.body();
      log.info("Received ProjectionData {} ", projectionData);
      circuitBreaker.fallback(throwable -> {
        log.error("Fallback for uowHandler ", throwable);
        return "fallback"; // WHAT TODO in this case?
      })
      .execute(uowHandler(projectionData))
      .setHandler(resultHandler(msg));
    }, resultHandler(msg));
  }

  Handler<Future<String>> uowHandler(final ProjectionData projectionData) {
    return (Future<String> future) -> {
      eventsProjector.handle(singletonList(projectionData));
      future.complete("roger that");
    };
  }

  Handler<AsyncResult<String>> resultHandler(final Message<ProjectionData> msg) {
    return (AsyncResult<String> resultHandler) -> {
      if (!resultHandler.succeeded()) {
        log.error("error cause: {}", resultHandler.cause());
        log.error("error message: {}", resultHandler.cause().getMessage());
        // resultHandler.cause().printStackTrace();
        // TODO customize given the commandResult
        msg.fail(400, resultHandler.cause().getMessage());
        return;
      }
      String resp = resultHandler.result();
      log.info("success: {}", resp);
      msg.reply(resp);
    };
  }

}
