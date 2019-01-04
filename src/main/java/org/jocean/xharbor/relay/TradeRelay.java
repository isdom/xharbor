/**
 *
 */
package org.jocean.xharbor.relay;

import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;

import org.jocean.http.FullMessage;
import org.jocean.http.MessageBody;
import org.jocean.http.TransportException;
import org.jocean.http.WriteCtrl;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.idiom.BeanFinder;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.StepableUtil;
import org.jocean.idiom.StopWatch;
import org.jocean.idiom.rx.RxObservables;
import org.jocean.idiom.rx.RxObservables.RetryPolicy;
import org.jocean.xharbor.api.TradeReactor;
import org.jocean.xharbor.api.TradeReactor.InOut;
import org.jocean.xharbor.api.TradeReactor.ReactContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.noop.NoopTracerFactory;
import io.opentracing.tag.Tags;
import rx.Observable;
import rx.Observable.Transformer;
import rx.Subscriber;
import rx.functions.Func1;

/**
 * @author isdom
 *
 */
public class TradeRelay extends Subscriber<HttpTrade> {

    private static final Logger LOG =
            LoggerFactory.getLogger(TradeRelay.class);

    public TradeRelay(final TradeReactor reactor) {
        this._tradeReactor = reactor;
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

        trade2ctx(trade).doOnNext(ctx -> ctxRef.set(ctx)).doOnNext(ctx -> hook4httpstatus(trade.writeCtrl(), ctx.span()))
            .flatMapSingle(ctx -> this._tradeReactor.react(ctx, new InOut() {
                @Override
                public Observable<FullMessage<HttpRequest>> inbound() {
                    return trade.inbound();
                }

                @Override
                public Observable<FullMessage<HttpResponse>> outbound() {
                    return null;
                }
            })).retryWhen(retryPolicy(trade)).subscribe(io -> {
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

    private void hook4httpstatus(final WriteCtrl writeCtrl, final Span span) {
        writeCtrl.sending().subscribe(obj -> {
            if ( obj instanceof HttpResponse) {
                final HttpResponse resp = (HttpResponse)obj;
                final int statusCode = resp.status().code();
                span.setTag(Tags.HTTP_STATUS.getKey(), statusCode);
                if (statusCode >= 300 && statusCode < 400) {
                    final String location = resp.headers().get(HttpHeaderNames.LOCATION);
                    if (null != location) {
                        span.setTag("http.location", location);
                    }
                }
                if (statusCode >= 400) {
                    span.setTag(Tags.ERROR.getKey(), true);
                }
            }
        });
    }

    private Observable<ReactContext> trade2ctx(final HttpTrade trade) {
        return trade.inbound().first().map(fullreq -> fullreq.message())
                .flatMap(request -> getTracer(request).map(tracer -> {
                    final Span span = tracer.buildSpan("httpin")
                    .withTag(Tags.COMPONENT.getKey(), "jocean-http")
                    .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
                    .withTag(Tags.HTTP_URL.getKey(), request.uri())
                    .withTag(Tags.HTTP_METHOD.getKey(), request.method().name())
                    .withTag(Tags.PEER_HOST_IPV4.getKey(), get1stIp(request.headers().get("x-forwarded-for", "none")))
                    .start();
                    trade.doOnTerminate(() -> span.finish());

                    // try to add host
                    addTagNotNull(span, "http.host", request.headers().get(HttpHeaderNames.HOST));
//                  SLB-ID头字段获取SLB实例ID。
//                  通过SLB-IP头字段获取SLB实例公网IP地址。
//                  通过X-Forwarded-Proto头字段获取SLB的监听协议

                    addTagNotNull(span, "slb.id", request.headers().get("slb-id"));
                    addTagNotNull(span, "slb.ip", request.headers().get("slb-ip"));
                    addTagNotNull(span, "slb.proto", request.headers().get("x-forwarded-proto"));

                    return buildReactCtx(trade, span, tracer);
                }));
    }

    private static void addTagNotNull(final Span span, final String tag, final String value) {
        if (null != value) {
            span.setTag(tag, value);
        }
    }

    private Observable<Tracer> getTracer(final HttpRequest request) {
        return this._tracingEnabled && isRequestFromSLB(request) ? this._finder.find(Tracer.class).onErrorReturn(e -> noopTracer)
                : Observable.just(noopTracer);
    }

    private boolean isRequestFromSLB(final HttpRequest request) {
        return request.headers().contains("x-forwarded-for");
    }

    private ReactContext buildReactCtx(final HttpTrade trade, final Span span, final Tracer tracer) {
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

    private static volatile Tracer noopTracer = NoopTracerFactory.create();

    @Inject
    BeanFinder _finder;

    @Value("${tracing.enabled}")
    boolean _tracingEnabled = true;

    @Value("${http.address}")
    String _httpAddress = "localhost";

    @Value("${http.port}")
    int _httpPort = 0;

    private final TradeReactor _tradeReactor;
    private final int _maxRetryTimes = 3;
    private final int _retryIntervalBase = 2;
}
