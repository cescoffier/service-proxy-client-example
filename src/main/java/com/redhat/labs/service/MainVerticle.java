package com.redhat.labs.service;

import com.redhat.labs.service.example.TestService;
import com.redhat.labs.service.example.TestServiceImpl;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
import io.vertx.ext.web.handler.sockjs.PermittedOptions;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.serviceproxy.ProxyHelper;
import io.vertx.serviceproxy.ServiceException;

import java.time.Instant;

import static io.netty.handler.codec.http.HttpHeaderValues.APPLICATION_JSON;
import static io.vavr.API.$;
import static io.vavr.API.Case;
import static io.vavr.API.Match;
import static io.vavr.Predicates.instanceOf;

public class MainVerticle extends AbstractVerticle {

    private static final String TEST_SERVICE = "test.service";
    private static final String PERIODIC_TIMER = "periodic.timer";

    @Override
    public void start(Future startFuture) {
        System.out.println("Hello !!");
        TestService svc = new TestServiceImpl(vertx);
        ProxyHelper.registerService(TestService.class, vertx, svc, TEST_SERVICE);

        vertx.setPeriodic(1000, (t) -> vertx.eventBus().publish(PERIODIC_TIMER, Instant.now().toString()));
        vertx.setPeriodic(125, (t) -> vertx.eventBus().publish(PERIODIC_TIMER, "SPACER"));

        Router router = Router.router(vertx);
        BridgeOptions bOpts = new BridgeOptions();
        bOpts.addInboundPermitted(new PermittedOptions()
                .setAddress(TEST_SERVICE));
        bOpts.addOutboundPermitted(new PermittedOptions()
                .setAddress(TEST_SERVICE)
                .setAddress(PERIODIC_TIMER));
        SockJSHandler sockJSHandler = SockJSHandler.create(vertx);
        sockJSHandler.bridge(bOpts);
        router.route("/eventbus/*").handler(sockJSHandler);

        router.route().handler(StaticHandler.create()
                .setCachingEnabled(false)
                .setIndexPage("index.html"));

        Router restV1 = Router.router(vertx);

        // Set all REST API requests to use JSON and handle uploads for appropriate HTTP verbs
        restV1.route()
                .consumes(APPLICATION_JSON.toString())
                .produces(APPLICATION_JSON.toString())
                .handler(ctx -> ctx.next());
        restV1.post()
                .handler(BodyHandler.create());
        restV1.put()
                .handler(BodyHandler.create());

        // Mount REST endpoints
        restV1.get("/test").handler(ctx -> svc.test(res -> this.genericResultHandler(ctx, res)));

        // Attach subrouter to main router
        router.mountSubRouter("/rest/v1", restV1);

        Future httpFuture = Future.future();

        vertx.createHttpServer().requestHandler(router::accept).listen(8080, startFuture.completer());
    }

    /**
     * Handle the result of a Service call and send the appropriate response
     * @param ctx The {@link RoutingContext} of the request
     * @param res The {@link AsyncResult} of the service call
     */
    void genericResultHandler(RoutingContext ctx, AsyncResult res) {
        if (res.succeeded()) {
            ctx.response().end(res.result().toString());
        } else {
            HttpResponseStatus status = HttpResponseStatus.valueOf(Match(res.cause()).of(
                Case($(instanceOf(ServiceException.class)), (c) -> ((ServiceException)c).failureCode()),
                Case($(instanceOf(ReplyException.class)), (c) -> ((ReplyException)c).failureCode()),
                Case($(), () -> 500)
            ));
            ctx.response()
                .setStatusMessage(status.reasonPhrase())
                .setStatusCode(status.code());
        }
    }
}
