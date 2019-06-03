/**
 *
 */
package org.jocean.xharbor.relay;

import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Named;

import org.jocean.http.FullMessage;
import org.jocean.http.MessageBody;
import org.jocean.http.TransportException;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.idiom.BeanFinder;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.StepableUtil;
import org.jocean.idiom.StopWatch;
import org.jocean.idiom.jmx.MBeanRegister;
import org.jocean.idiom.jmx.MBeanRegisterAware;
import org.jocean.idiom.rx.RxObservables;
import org.jocean.idiom.rx.RxObservables.RetryPolicy;
import org.jocean.svr.TradeScheduler;
import org.jocean.svr.tracing.TraceUtil;
import org.jocean.xharbor.api.TradeReactor;
import org.jocean.xharbor.api.TradeReactor.InOut;
import org.jocean.xharbor.api.TradeReactor.ReactContext;
import org.jocean.xharbor.reactor.NullReactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.hystrix.HystrixCommandProperties.ExecutionIsolationStrategy;
import com.netflix.hystrix.HystrixObservableCommand;

import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.CharsetUtil;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.noop.NoopTracerFactory;
import io.opentracing.tag.Tags;
import rx.Observable;
import rx.Observable.Transformer;
import rx.Scheduler;
import rx.Subscriber;
import rx.functions.Func0;
import rx.functions.Func1;

/**
 * @author isdom
 *
 */
public class TradeRelay extends Subscriber<HttpTrade> implements TradeRelayMXBean, MBeanRegisterAware {

    private static final Logger LOG = LoggerFactory.getLogger(TradeRelay.class);

    @Override
    public void setMBeanRegister(final MBeanRegister register) {
        register.registerMBean("name=relay", this);
    }

    @Override
    public Map<String, String> getSchedulers() {
        final Map<String, String> schedulers = new HashMap<>();
        for ( final Map.Entry<String, TradeScheduler> entry : this._requestSchedulers.entrySet()) {
            schedulers.put(entry.getKey(), entry.getValue().toString());
        }
        return schedulers;
    }

    @Override
    public Map<String, String> getIsolations() {
        final Map<String, String> isolations = new HashMap<>();
        for ( final Map.Entry<String, RequestIsolation> entry : this._requestIsolations.entrySet()) {
            isolations.put(entry.getKey(), entry.getValue().toString());
        }
        return isolations;
    }

    @Override
    public boolean isTracingEnabled() {
        return _tracingEnabled;
    }

    @Override
    public String[] getReactors() {
        final TradeReactor reactor = this._reactorRef.get();
        return null != reactor ? reactor.reactItems() : NullReactor.INSTANCE.reactItems();
    }

    @Override
    public void onCompleted() {
        LOG.warn("TradeRelay {} onCompleted", this);
    }

    @Override
    public void onError(final Throwable e) {
        LOG.warn("TradeRelay {} onError, detail:{}", this, ExceptionUtils.exception2detail(e));
    }

    public static String get1stIp(final String peerips) {
        return peerips.split(",")[0];
    }

    @Override
    public void onNext(final HttpTrade trade) {
        LOG.trace("TradeRelay {} onNext, trade {}", this, trade);

        final AtomicReference<ReactContext> ctxRef = new AtomicReference<>();

        trade2io(trade, ctxRef).retryWhen(retryPolicy(trade))
        .subscribe(io -> {
            if (null == io || null == io.outbound()) {
                LOG.warn("NO_INOUT for trade({}), react io detail: {}.", trade, io);
                ctxRef.get().span().setTag(Tags.ERROR.getKey(), true);
                ctxRef.get().span().setTag("error.type", "NO_INOUT");
            }
            trade.outbound(buildResponse(trade, io).compose(fullresp2objs()));
        }, error -> {
            LOG.warn("Trade {} react with error, detail:{}", trade, ExceptionUtils.exception2detail(error));
            ctxRef.get().span().setTag(Tags.ERROR.getKey(), true);
            ctxRef.get().span().log(Collections.singletonMap("error.detail", ExceptionUtils.exception2detail(error)));
            trade.close();
        });
    }

    private InOut initial_io(final HttpTrade trade, final Observable<FullMessage<HttpResponse>> outbound) {
        return new InOut() {
            @Override
            public Observable<FullMessage<HttpRequest>> inbound() {
                return trade.inbound();
            }

            @Override
            public Observable<FullMessage<HttpResponse>> outbound() {
                return outbound;
            }
        };
    }

    private Observable<InOut> trade2io(final HttpTrade trade, final AtomicReference<ReactContext> ctxRef) {
        return trade.inbound().first().map(fullreq -> fullreq.message()).flatMap(request -> {
                    final String path = extractPath(request);
                    LOG.info("trade2io: {} extract path {}", trade, path);
                    return path2scheduler(path).doOnNext(ts -> LOG.info("path {} <--> scheduler {}", path, ts))
                            .flatMap(ts -> makectx(request, trade, ts.scheduler(), ts.workerCount())
                            .doOnNext(ctx -> LOG.info("trade2io: {} handle with ctx {}", trade, ctx))
                            .doOnNext(ctx -> ctxRef.set(ctx))
                            .flatMap(ctx -> {
                                final Observable<? extends InOut> reaction = getReactor().flatMap(reactor -> reactor.react(ctx, initial_io(trade, null)).toObservable());
                                final RequestIsolation req_isolation = path2isolation(path);
                                if (null != req_isolation) {
                                    return enableIsolation(req_isolation, reaction,
                                            () -> fallbackOutbound(ctx.span(), req_isolation, request.protocolVersion(), trade));
                                } else {
                                    return reaction;
                                }
                            }));
                });
    }

    private Observable<TradeReactor> getReactor() {
        final TradeReactor reactor = this._reactorRef.get();
        return null != reactor ? Observable.just(reactor) : findAndSetRouter();
    }

    private Observable<InOut> fallbackOutbound(final Span span, final RequestIsolation req_isolation, final HttpVersion protocolVersion,
            final HttpTrade trade) {
        final HttpResponse response = new DefaultHttpResponse(protocolVersion, HttpResponseStatus.FOUND);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        response.headers().set(HttpHeaderNames.LOCATION, req_isolation._location);

        span.setOperationName(req_isolation._path);
        span.setTag("fallback", true);

        return Observable.just(initial_io(trade, responseWithoutBody(response)));
    }

    private Observable<? extends InOut> enableIsolation(
            final RequestIsolation req_isolation,
            final Observable<? extends InOut> normal,
            final Func0<Observable<InOut>> getfallback) {
        return new HystrixObservableCommand<InOut>(HystrixObservableCommand.Setter
                .withGroupKey(HystrixCommandGroupKey.Factory.asKey("req_isolations"))
                .andCommandKey(HystrixCommandKey.Factory.asKey(req_isolation._path))
                .andCommandPropertiesDefaults(HystrixCommandProperties.Setter()
                        .withExecutionTimeoutEnabled(req_isolation._timeoutInMs > 0)
                        .withExecutionTimeoutInMilliseconds(req_isolation._timeoutInMs > 0 ? req_isolation._timeoutInMs : 0)
                        .withExecutionIsolationStrategy(ExecutionIsolationStrategy.SEMAPHORE)
                        .withExecutionIsolationSemaphoreMaxConcurrentRequests(req_isolation._maxConcurrent)
                        .withFallbackIsolationSemaphoreMaxConcurrentRequests(100)
                        )
                ) {
            @Override
            protected Observable<InOut> construct() {
                return (Observable<InOut>) normal;
            }

            @Override
            protected Observable<InOut> resumeWithFallback() {
                return getfallback.call();
            }
        }.toObservable();
    }

    private RequestIsolation path2isolation(final String path) {
        return this._requestIsolations.get(path);
    }

    private static String extractPath(final HttpRequest request) {
        final QueryStringDecoder decoder = new QueryStringDecoder(request.uri(), CharsetUtil.UTF_8);
        final String path = decoder.path();
        return path;
    }

    private static Observable<FullMessage<HttpResponse>> responseWithoutBody(final HttpResponse response) {
        return Observable.just(new FullMessage<HttpResponse>() {
            @Override
            public HttpResponse message() {
                return response;
            }
            @Override
            public Observable<? extends MessageBody> body() {
                return Observable.empty();
            }});
    }

    private Observable<ReactContext> makectx(
            final HttpRequest request,
            final HttpTrade trade,
            final Scheduler scheduler,
            final int concurrent) {
        return getTracer(request)
                //.subscribeOn(scheduler)
        .map(tracer -> {
            final Span span = tracer.buildSpan("httpin")
            .withTag(Tags.COMPONENT.getKey(), "jocean-http")
            .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
            .withTag(Tags.HTTP_URL.getKey(), request.uri())
            .withTag(Tags.HTTP_METHOD.getKey(), request.method().name())
            .withTag(Tags.PEER_HOST_IPV4.getKey(), get1stIp(request.headers().get("x-forwarded-for", "none")))
            .start();
            trade.doOnHalt(() -> span.finish());

            // try to add host
            TraceUtil.addTagNotNull(span, "http.host", request.headers().get(HttpHeaderNames.HOST));
//                  SLB-ID头字段获取SLB实例ID。
//                  通过SLB-IP头字段获取SLB实例公网IP地址。
//                  通过X-Forwarded-Proto头字段获取SLB的监听协议

            TraceUtil.addTagNotNull(span, "slb.id", request.headers().get("slb-id"));
            TraceUtil.addTagNotNull(span, "slb.ip", request.headers().get("slb-ip"));
            TraceUtil.addTagNotNull(span, "slb.proto", request.headers().get("x-forwarded-proto"));
            TraceUtil.hook4serversend(trade.writeCtrl(), span);

            return buildReactCtx(trade, span, tracer, scheduler, concurrent);
        }).observeOn(scheduler, this._maxPending);
    }

    private Observable<TradeScheduler> path2scheduler(final String path) {
        final TradeScheduler ts = _requestSchedulers.get(path);
        return (null != ts ? Observable.just(ts) : _finder.find(this._schedulerName, TradeScheduler.class));
    }

//    private <T> Transformer<T, T> runWithin(final AtomicReference<Scheduler> schedulerRef, final AtomicInteger concurrent) {
//        return ts -> _finder.find(this._schedulerName, TradeScheduler.class)
//                .doOnNext(scheduler -> {
//                    schedulerRef.set(scheduler.scheduler());
//                    concurrent.set(scheduler._workerCount);
//                })
//                .flatMap(scheduler ->  ts.observeOn(scheduler.scheduler(), this._maxPending));
//    }

    private Observable<Tracer> getTracer(final HttpRequest request) {
        return this._tracingEnabled && isRequestForwardBySLB(request)
                ? this._finder.find(Tracer.class).onErrorReturn(e -> noopTracer)
                : Observable.just(noopTracer);
    }

    private boolean isRequestForwardBySLB(final HttpRequest request) {
        return request.headers().contains("x-forwarded-for");
    }

    private ReactContext buildReactCtx(final HttpTrade trade, final Span span, final Tracer tracer, final Scheduler scheduler, final int concurrent) {
        final StopWatch watch4Result = new StopWatch();
        return new ReactContext() {
            @Override
            public HttpTrade trade() {
                return trade;
            }

            @Override
            public StopWatch watch() {
                return watch4Result;
            }

            @Override
            public Span span() {
                return span;
            }

            @Override
            public Tracer tracer() {
                return tracer;
            }

            @Override
            public Scheduler scheduler() {
                return scheduler;
            }

            @Override
            public int concurrent() {
                return concurrent;
            }};
    }

    private Transformer<FullMessage<HttpResponse>, Object> fullresp2objs() {
        return getfullresp -> getfullresp.flatMap(fullresp ->
            Observable.<Object>just(fullresp.message()).concatWith(fullresp.body().concatMap(body -> body.content()))
                .concatWith(Observable.just(LastHttpContent.EMPTY_LAST_CONTENT)));
    }

    private Observable<FullMessage<HttpResponse>> buildResponse(final HttpTrade trade, final InOut io) {
        return (null != io && null != io.outbound()) ? io.outbound() : defaultResponse200(trade);
    }

    private Observable<FullMessage<HttpResponse>> defaultResponse200(final HttpTrade trade) {
        return trade.inbound().map(fullreq -> (FullMessage<HttpResponse>)new FullMessage<HttpResponse>() {
            @Override
            public HttpResponse message() {
                final HttpResponse response = new DefaultHttpResponse(
                        fullreq.message().protocolVersion(), HttpResponseStatus.OK);
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
                return response;
            }
            @Override
            public Observable<? extends MessageBody> body() {
                return Observable.empty();
            }} )
            // response when request send completed
            .delaySubscription(trade.inbound().flatMap(fullmsg -> fullmsg.body()).flatMap(body -> body.content())
                .compose(StepableUtil.autostep2element2()).doOnNext(bbs -> bbs.dispose()).ignoreElements());
    }

    @SuppressWarnings("unchecked")
    private Func1<Observable<? extends Throwable>, ? extends Observable<?>> retryPolicy(final HttpTrade trade) {
        final RetryPolicy<Integer> policy = new RetryPolicy<Integer>() {
            @Override
            public Observable<Integer> call(final Observable<Throwable> errors) {
                return (Observable<Integer>) errors.compose(RxObservables.retryIfMatch(ifMatch(trade), 100))
                        .compose(RxObservables.retryMaxTimes(_maxRetryTimes))
                        .compose(RxObservables.retryDelayTo(_retryIntervalBase))
                        .doOnNext( retryCount ->
                            LOG.info("FORWARD_RETRY with retry-count {} for trade {}", retryCount, trade))
                        ;
            }};
        return errors -> policy.call((Observable<Throwable>)errors);
    }

    private static Func1<Throwable, Boolean> ifMatch(final HttpTrade trade) {
        return new Func1<Throwable, Boolean>() {
            @Override
            public Boolean call(final Throwable e) {
                //  TODO, check obsrequest has been disposed ?
//                if (trade.inboundHolder().isFragmented()) {
//                    LOG.warn("NOT_RETRY for trade({}), bcs of trade's inbound has fragmented.",
//                            trade);
//                    return false;
//                }
                final boolean matched = (e instanceof TransportException)
                    || (e instanceof ConnectException)
                    || (e instanceof ClosedChannelException);
                if (matched) {
                    LOG.info("RETRY for trade({}), bcs of error: {}",
                            trade, ExceptionUtils.exception2detail(e));
                } else {
                    LOG.warn("NOT_RETRY for trade({}), bcs of error: {}",
                            trade, ExceptionUtils.exception2detail(e));
                }
                return matched;
            }
            @Override
            public String toString() {
                return "TransportException or ConnectException or ClosedChannelException";
            }
        };
    }

    private Observable<TradeReactor> findAndSetRouter() {
        return this._finder.find(this._routerName, TradeReactor.class).map(reactor -> {
            if (this._reactorRef.compareAndSet(null, reactor)) {
                LOG.info("found router {} with name: {}", reactor, this._routerName);
                return reactor;
            } else {
                LOG.info("using router {} with name: {}", reactor, this._routerName);
                return this._reactorRef.get();
            }
        })
        .doOnError( e -> LOG.warn("can't found router named {}", _routerName) )
        .onErrorReturn(e -> NullReactor.INSTANCE);
    }

    private static volatile Tracer noopTracer = NoopTracerFactory.create();

    @Inject
    BeanFinder _finder;

    @Value("${scheduler.name}")
    String _schedulerName = "scheduler_default";

    @Value("${max.pending}")
    int _maxPending = 1024;

    @Value("${tracing.enabled}")
    boolean _tracingEnabled = true;

    @Value("${http.address}")
    String _httpAddress = "localhost";

    @Value("${http.port}")
    int _httpPort = 0;

    @Inject
    @Named("req_isolations")
    Map<String, RequestIsolation> _requestIsolations;

    @Inject
    @Named("req_schedulers")
    Map<String, TradeScheduler> _requestSchedulers;

    @Value("${router.name}")
    String _routerName = "default_router";

    final private AtomicReference<TradeReactor> _reactorRef = new AtomicReference<>(null);;

    private final int _maxRetryTimes = 3;
    private final int _retryIntervalBase = 2;
}
