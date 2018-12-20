package org.jocean.xharbor.reactor;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.Feature;
import org.jocean.http.FullMessage;
import org.jocean.http.MessageBody;
import org.jocean.http.TrafficCounter;
import org.jocean.http.TransportException;
import org.jocean.http.WriteCtrl;
import org.jocean.http.client.HttpClient;
import org.jocean.http.client.HttpClient.HttpInitiator;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.idiom.BeanFinder;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.StopWatch;
import org.jocean.idiom.rx.RxObservables;
import org.jocean.xharbor.api.RelayMemo;
import org.jocean.xharbor.api.RelayMemo.RESULT;
import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.api.ServiceMemo;
import org.jocean.xharbor.api.Target;
import org.jocean.xharbor.api.TradeReactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.hystrix.HystrixObservableCommand;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.Timer;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import io.opentracing.tag.Tags;
import rx.Observable;
import rx.Observable.Transformer;
import rx.Single;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;

public class ForwardTrade implements TradeReactor {

    private static final int MAX_RETAINED_SIZE = 8 * 1024;
    private static final long _period = 20; // 30 seconds
    private static final Logger LOG = LoggerFactory.getLogger(ForwardTrade.class);

    public ForwardTrade(
            final MatchRule  matcher,
            final BeanFinder finder,
            final RelayMemo.Builder memoBuilder,
            final ServiceMemo serviceMemo,
            final Timer timer
            ) {
        this._matcher = matcher;
        this._finder = finder;
        this._memoBuilder = memoBuilder;
        this._serviceMemo = serviceMemo;
        this._timer = timer;
    }

    @Override
    public String toString() {
        final int maxLen = 10;
        final StringBuilder builder = new StringBuilder();
        builder.append("ForwardTrade [matcher=").append(_matcher).append(", targets=")
                .append(_targets != null ? _targets.subList(0, Math.min(_targets.size(), maxLen)) : null).append("]");
        return builder.toString();
    }

    public void addTarget(final Target target) {
        this._targets.add(new MarkableTargetImpl(target));
    }

    @Override
    public Single<? extends InOut> react(final ReactContext ctx, final InOut io) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("try {} for trade {}", this, ctx.trade());
        }
        if (null != io.outbound()) {
            return Single.<InOut>just(null);
        }
        return io.inbound().first().flatMap(fullreq -> {
            if (this._matcher.match(fullreq.message())) {
                final MarkableTargetImpl target = selectTarget();
                if (null == target) {
                    // no target
                    LOG.warn("NONE_TARGET to forward for trade {}", ctx.trade());
                    return Observable.just(null);
                } else {
                    LOG.debug("forward to {} for trade {}", target, ctx.trade());
                    return io4forward(ctx, io, target, this._matcher.summary());
                }
            } else {
                // not handle this trade
                return Observable.just(null);
            }
        }).compose(RxObservables.<InOut>ensureSubscribeAtmostOnce()).toSingle();
    }

    private Observable<InOut> io4forward(final ReactContext ctx,
            final InOut orgio,
            final MarkableTargetImpl target,
            final String summary) {
        return new HystrixObservableCommand<InOut>(HystrixObservableCommand.Setter
                        .withGroupKey(HystrixCommandGroupKey.Factory.asKey("forward"))
                        .andCommandKey(HystrixCommandKey.Factory.asKey(summary + "-request"))
                        .andCommandPropertiesDefaults(HystrixCommandProperties.Setter()
//                                .withExecutionTimeoutEnabled(false)
                                .withExecutionTimeoutInMilliseconds(30 * 1000)
                                .withExecutionIsolationSemaphoreMaxConcurrentRequests(1000)
                                )
                        ) {
                    @Override
                    protected Observable<InOut> construct() {
                        return buildOutbound(ctx, orgio.inbound(), target)
                            .doOnError(onCommunicationError(target)).compose(makeupio(orgio, target, ctx, summary)).first();
                    }
                }.toObservable();
    }

    private Transformer<FullMessage<HttpResponse>, InOut> makeupio(
            final InOut orgio,
            final MarkableTargetImpl target,
            final ReactContext ctx,
            final String summary) {
        return getfullresp -> {
            final Observable<FullMessage<HttpResponse>> cached = getfullresp.cache();

            cached.subscribe(fullresp -> LOG.debug("try get resp: {}", fullresp.message()));

            //  启动转发 (forward)
            return cached.flatMap(fullresp -> {
                LOG.debug("recv response head part {}.", fullresp.message());

                // 404 Not Found
                if (fullresp.message().status().equals(HttpResponseStatus.NOT_FOUND)) {
                    // Request-URI not found in target service, so try next
                    // matched forward target
                    LOG.info("API_NOT_SUPPORTED for target {}, so forward trade({}) to next reactor", target,
                            ctx.trade());
                    return Observable.<InOut>just(null);
                }

                // 5XX Server Internal Error
                if (fullresp.message().status().code() >= 500) {
                    // Server Internal Error
                    target.markAPIDownStatus(true);
                    LOG.warn("SERVER_ERROR({}), so mark service [{}]'s matched {} APIs down.", fullresp.message().status(),
                            target.serviceUri(), _matcher);
                    _timer.newTimeout(timeout -> {
                        // reset down flag
                        target.markAPIDownStatus(false);
                        LOG.info(
                                "reset service [{}]'s matched {} APIs down to false, after {} second cause by SERVER_ERROR({}).",
                                target.serviceUri(), _matcher, _period, fullresp.message().status());
                    }, _period, TimeUnit.SECONDS);
                    return Observable.error(new TransportException("SERVER_ERROR(" + fullresp.message().status() + ")"));
                }

//                ctx.span().setOperationName(this._matcher.summary());

                return Observable.<InOut>just(new InOut() {
                    @Override
                    public Observable<FullMessage<HttpRequest>> inbound() {
                        return orgio.inbound();
                    }

                    @Override
                    public Observable<FullMessage<HttpResponse>> outbound() {
                        return cached.doOnCompleted(()-> LOG.info("forward outbound completed"));
                    }
                });
            });
        };
    }

    private Action1<? super Throwable> onCommunicationError(final MarkableTargetImpl target) {
        return error -> {
            // remember reset to false after a while
            if (isCommunicationFailure(error)) {
                markServiceDownStatus(target, true);
                LOG.warn("COMMUNICATION_FAILURE({}), so mark service [{}] down.",
                        ExceptionUtils.exception2detail(error), target.serviceUri());
                _timer.newTimeout(timeout -> {
                        // reset down flag
                        markServiceDownStatus(target, false);
                        LOG.info(
                                "reset service [{}] down to false, after {} second cause by COMMUNICATION_FAILURE({}).",
                                target.serviceUri(), _period, ExceptionUtils.exception2detail(error));
                    }, _period, TimeUnit.SECONDS);
            }
        };
    }

    private Observable<FullMessage<HttpResponse>> buildOutbound(
            final ReactContext ctx,
            final Observable<FullMessage<HttpRequest>> inbound,
            final Target target) {
        final HttpTrade trade = ctx.trade();
        final StopWatch stopWatch = ctx.watch();

        return forwardTo(target).doOnNext(upstream->trade.doOnTerminate(upstream.closer()))
                .flatMap(upstream -> {
                    final AtomicBoolean isKeepAliveFromClient = new AtomicBoolean(true);
                    final AtomicReference<HttpRequest> refReq = new AtomicReference<>();
                    final AtomicReference<HttpResponse> refResp = new AtomicReference<>();

                    final TrafficCounter upstreamTraffic = upstream.traffic();
                    trade.doOnTerminate(() -> {
                            final long ttl = stopWatch.stopAndRestart();
                            final RelayMemo memo = _memoBuilder.build(target, buildRoutingInfo(refReq.get()));
                            memo.incBizResult(RESULT.RELAY_SUCCESS, ttl);
                            LOG.info("FORWARD_SUCCESS" + "\ncost:[{}]s,forward_to:[{}]"
                                            + "\nINCOME:channel:{},request:[{}]bytes,response:[{}]bytes"
                                            + "\nUPSTREAM:channel:{},request:[{}]bytes,response:[{}]bytes"
                                            + "\nREQ\n[{}]\nsendback\nRESP\n[{}]",
                                    ttl / (float) 1000.0,
                                    target.serviceUri(),
                                    trade.transport(),
                                    trade.traffic().inboundBytes(),
                                    trade.traffic().outboundBytes(),
                                    upstream.transport(),
                                    upstreamTraffic.outboundBytes(),
                                    upstreamTraffic.inboundBytes(),
                                    refReq.get(),
                                    refResp.get()
                                    );
                        });

                    enableDisposeSended(upstream.writeCtrl(), MAX_RETAINED_SIZE);

                    return ctx2span(ctx, target)
                            .flatMap(span ->  isDBS().doOnNext(configDBS(trade))
                            .flatMap(any -> upstream.defineInteraction(
                                    inbound.map(addKeepAliveIfNeeded(refReq, isKeepAliveFromClient))
                                    .flatMap(fullreq ->
                                        this._finder.find(Tracer.class).map(tracer -> {
                                            tracer.inject(span.context(), Format.Builtin.HTTP_HEADERS, message2textmap(fullreq.message()));
                                            return fullreq;
                                        }))
                                    .compose(fullreq2objs())))
                                .map(removeKeepAliveIfNeeded(refResp, isKeepAliveFromClient))
                                .doOnNext(fullresp -> span.setTag(Tags.HTTP_STATUS.getKey(), fullresp.message().status().code()))
                                .doOnTerminate(() -> span.finish())
                            );
                });
    }

    private Observable<Span> ctx2span(final ReactContext ctx, final Target target) {
        return this._finder.find(Tracer.class).map(tracer -> tracer.buildSpan(this._matcher.summary())
            .withTag(Tags.COMPONENT.getKey(), "jocean-http")
            .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
    //                    .withTag(Tags.HTTP_URL.getKey(), request.uri())
    //                    .withTag(Tags.HTTP_METHOD.getKey(), request.method().name())
            .withTag(Tags.PEER_HOST_IPV4.getKey(), target.serviceUri().getHost())
            .withTag(Tags.PEER_PORT.getKey(), target.serviceUri().getPort()) // TODO add service
            .asChildOf(ctx.span())
            .start());
    }

    private static TextMap message2textmap(final HttpMessage message) {
        return new TextMap() {
            @Override
            public Iterator<Entry<String, String>> iterator() {
                return message.headers().iteratorAsString();
            }

            @Override
            public void put(final String key, final String value) {
                message.headers().set(key, value);
            }};
    }

    private Transformer<FullMessage<HttpRequest>, Object> fullreq2objs() {
        return getfullreq -> getfullreq.flatMap(fullreq -> Observable.<Object>just(fullreq.message()).concatWith(fullreq.body().concatMap(body -> body.content()))
                .concatWith(Observable.just(LastHttpContent.EMPTY_LAST_CONTENT)));
    }

    private Action1<? super Boolean> configDBS(final HttpTrade trade) {
        return dbs-> {
            LOG.info("forward: pathPattern {}'s dbs {}", _matcher.pathPattern(), dbs);
            if (dbs) {
                // 对已经发送成功的 DisposableWrapper<?>，及时 invoke it's dispose() 回收占用的资源 (memory, ...)
                trade.writeCtrl().sended().subscribe(sended -> DisposableWrapperUtil.dispose(sended));
            }
        };
    }

    private Observable<? extends HttpInitiator> forwardTo(final Target target) {
        return this._finder.find(HttpClient.class).flatMap(client -> client.initiator()
                .remoteAddress(buildAddress(target.serviceUri())).feature(target.features().call())
                .feature(Feature.ENABLE_LOGGING_OVER_SSL)
                .build());
    }

    private void enableDisposeSended(final WriteCtrl writeCtrl, final int size) {
        final AtomicInteger sendingSize = new AtomicInteger(0);

        writeCtrl.sending().subscribe(sending -> sendingSize.addAndGet(getReadableBytes(sending)));
        writeCtrl.sended().subscribe(sended -> {
            if (sendingSize.get() > size) {
                LOG.trace("sendingSize is {}, dispose sended {}", sendingSize.get(), sended);
                DisposableWrapperUtil.dispose(sended);
            } else {
                LOG.trace("sendingSize is {}, SKIP sended {}", sendingSize.get(), sended);
            }
        });
    }

//    private Observable<Boolean> isRBS() {
//        return this._finder.find("configs", Map.class).map(conf -> !istrue(conf.get(_matcher.pathPattern() + ":" + "disable_rbs")));
//    }

    private Observable<Boolean> isDBS() {
        return this._finder.find("configs", Map.class).map(conf -> !istrue(conf.get(_matcher.pathPattern() + ":" + "disable_dbs")));
    }

    private static boolean istrue(final Object value) {
        return value == null ? false : value.toString().equals("true");
    }

    private int getReadableBytes(final Object sending) {
        final Object unwrap = DisposableWrapperUtil.unwrap(sending);
        return unwrap instanceof HttpContent ? ((HttpContent) unwrap).content().readableBytes() : 0;
    }

    private InetSocketAddress buildAddress(final URI uri) {
        return new InetSocketAddress(uri.getHost(), uri.getPort());
    }

    private  Func1<FullMessage<HttpRequest>, FullMessage<HttpRequest>> addKeepAliveIfNeeded(
            final AtomicReference<HttpRequest> refReq,
            final AtomicBoolean isKeepAliveFromClient) {
        return fullreq -> {
            refReq.set(fullreq.message());
            // only check first time, bcs inbound could be process many
            // times
            if (isKeepAliveFromClient.get()) {
                isKeepAliveFromClient.set(HttpUtil.isKeepAlive(fullreq.message()));
                if (!isKeepAliveFromClient.get()) {
                    // if NOT keep alive, force it
                    final HttpRequest newreq = new DefaultHttpRequest(
                            fullreq.message().protocolVersion(),
                            fullreq.message().method(),
                            fullreq.message().uri());
                    newreq.headers().add(fullreq.message().headers());
                    newreq.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                    LOG.info("FORCE-KeepAlive: add Connection header with KeepAlive for incoming req:\n[{}]", fullreq.message());
                    return new FullMessage<HttpRequest>() {
                        @Override
                        public HttpRequest message() {
                            return newreq;
                        }
                        @Override
                        public Observable<? extends MessageBody> body() {
                            return fullreq.body();
                        }};
                }
            }
            return fullreq;
        };
    }

    private Func1<FullMessage<HttpResponse>, FullMessage<HttpResponse>> removeKeepAliveIfNeeded(
            final AtomicReference<HttpResponse> refResp,
            final AtomicBoolean isKeepAliveFromClient) {
        return fullresp -> {
            refResp.set(fullresp.message());
            if (!isKeepAliveFromClient.get()) {
                if (HttpUtil.isKeepAlive(fullresp.message())) {
                    // if NOT keep alive from client, remove keepalive
                    // header
                    final HttpResponse newresp = new DefaultHttpResponse(fullresp.message().protocolVersion(),
                            fullresp.message().status());
                    newresp.headers().add(fullresp.message().headers());
                    newresp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                    LOG.info("FORCE-KeepAlive: set Connection header with Close for sendback resp:\n[{}]", fullresp.message());
                    return new FullMessage<HttpResponse>() {
                        @Override
                        public HttpResponse message() {
                            return newresp;
                        }
                        @Override
                        public Observable<? extends MessageBody> body() {
                            return fullresp.body();
                        }};
                }
            }
            return fullresp;
        };
    }

    private MarkableTargetImpl selectTarget() {
        int total = 0;
        MarkableTargetImpl best = null;
        for ( final MarkableTargetImpl peer : this._targets ) {
            if ( isTargetActive(peer) ) {
                // nginx C code: peer->current_weight += peer->effective_weight;
                final int effectiveWeight = peer._effectiveWeight.get();
                final int currentWeight = peer._currentWeight.addAndGet( effectiveWeight );
                total += effectiveWeight;
//  nginx C code:
//                if (best == NULL || peer->current_weight > best->current_weight) {
//                    best = peer;
//                }
                if ( null == best || best._currentWeight.get() < currentWeight ) {
                    best = peer;
                }
            }
        }

        if (null == best) {
            return null;
        }

// nginx C code: best->current_weight -= total;
        best._currentWeight.addAndGet(-total);

        return best;
    }

    private boolean isTargetActive(final MarkableTargetImpl target) {
        return !(this._serviceMemo.isServiceDown(target.serviceUri()) || target._down.get());
    }

    private void markServiceDownStatus(final Target target, final boolean isDown) {
        this._serviceMemo.markServiceDownStatus(target.serviceUri(), isDown);
    }

    private RoutingInfo buildRoutingInfo(final HttpRequest req) {
        final String path = pathOf(req);
        return new RoutingInfo() {
            @Override
            public String getMethod() {
                return req.method().name();
            }

            @Override
            public String getPath() {
                return path;
            }};
    }

    private String pathOf(final HttpRequest req) {
        final QueryStringDecoder decoder = new QueryStringDecoder(req.uri());

        String path = decoder.path();
        final int p = path.indexOf(";");
        if (p>-1) {
            path = path.substring(0, p);
        }
        return path;
    }

    private boolean isCommunicationFailure(final Throwable error) {
        return error instanceof ConnectException;
    }

    private class MarkableTargetImpl implements Target {

        private static final int MAX_EFFECTIVEWEIGHT = 1000;

        MarkableTargetImpl(final Target target) {
            this._target = target;
        }

        @Override
        public URI serviceUri() {
            return this._target.serviceUri();
        }

        @Override
        public Func0<Feature[]> features() {
            return this._target.features();
        }

        @SuppressWarnings("unused")
        public int addWeight(final int deltaWeight) {
            int weight = this._effectiveWeight.addAndGet(deltaWeight);
            if ( weight > MAX_EFFECTIVEWEIGHT ) {
                weight = this._effectiveWeight.addAndGet(-deltaWeight);
            }
            return weight;
        }

        public void markAPIDownStatus(final boolean isDown) {
            this._down.set(isDown);
        }

        private final Target _target;
        private final AtomicInteger _currentWeight = new AtomicInteger(1);
        private final AtomicInteger _effectiveWeight = new AtomicInteger(1);
        private final AtomicBoolean _down = new AtomicBoolean(false);
    }

    private final MatchRule     _matcher;
    private final List<MarkableTargetImpl>  _targets =
            Lists.newCopyOnWriteArrayList();

    private final BeanFinder    _finder;
    private final RelayMemo.Builder _memoBuilder;
    private final ServiceMemo   _serviceMemo;
    private final Timer         _timer;
}
