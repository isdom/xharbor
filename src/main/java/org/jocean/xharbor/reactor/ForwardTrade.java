package org.jocean.xharbor.reactor;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.Feature;
import org.jocean.http.HttpSlice;
import org.jocean.http.HttpSliceUtil;
import org.jocean.http.TrafficCounter;
import org.jocean.http.TransportException;
import org.jocean.http.WriteCtrl;
import org.jocean.http.client.HttpClient;
import org.jocean.http.client.HttpClient.HttpInitiator;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.BeanFinder;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.StopWatch;
import org.jocean.idiom.rx.RxObservables;
import org.jocean.xharbor.HttpSliceX;
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
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.Timer;
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

    public void addTarget(final Target target) {
        this._targets.add(new MarkableTargetImpl(target));
    }

    @Override
    public Single<? extends InOut> react(final ReactContext ctx, final InOut io) {
        if (null != io.outbound()) {
            return Single.<InOut>just(null);
        }
        return io.inbound().compose(HttpSliceUtil.<HttpRequest>extractHttpMessage()).flatMap(req -> {
            if (null == req) {
                return null;
            } else {
                if (this._matcher.match(req)) {
                    final MarkableTargetImpl target = selectTarget();
                    if (null == target) {
                        // no target
                        LOG.warn("NONE_TARGET to forward for trade {}", ctx.trade());
                        return Observable.just(null);
                    } else {
                        return io4forward(ctx, io, target, this._matcher.summary());
                    }
                } else {
                    // not handle this trade
                    return Observable.just(null);
                }
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
                        return buildOutbound(ctx.trade(), orgio.inbound(), target, ctx.watch())
                            .doOnError(onCommunicationError(target)).compose(makeupio(orgio, target, ctx, summary)).first();
                    }
                }.toObservable();
    }

    private Transformer<HttpSlice, InOut> makeupio(
            final InOut orgio,
            final MarkableTargetImpl target,
            final ReactContext ctx,
            final String summary) {
        return slices -> {
            final Observable<? extends HttpSlice> cached = slices.cache();
            //  启动转发 (forward)
            return cached.compose(HttpSliceUtil.<HttpResponse>extractHttpMessage()).flatMap(resp -> {
                LOG.debug("recv first response head part {}.", resp);

                // 404 Not Found
                if (resp.status().equals(HttpResponseStatus.NOT_FOUND)) {
                    // Request-URI not found in target service, so try next
                    // matched forward target
                    LOG.info("API_NOT_SUPPORTED for target {}, so forward trade({}) to next reactor", target,
                            ctx.trade());
                    return Observable.<InOut>just(null);
                }

                // 5XX Server Internal Error
                if (resp.status().code() >= 500) {
                    // Server Internal Error
                    target.markAPIDownStatus(true);
                    LOG.warn("SERVER_ERROR({}), so mark service [{}]'s matched {} APIs down.", resp.status(),
                            target.serviceUri(), _matcher);
                    _timer.newTimeout(timeout -> {
                        // reset down flag
                        target.markAPIDownStatus(false);
                        LOG.info(
                                "reset service [{}]'s matched {} APIs down to false, after {} second cause by SERVER_ERROR({}).",
                                target.serviceUri(), _matcher, _period, resp.status());
                    }, _period, TimeUnit.SECONDS);
                    return Observable.error(new TransportException("SERVER_ERROR(" + resp.status() + ")"));
                }

                ctx.trade().writeCtrl().sended().subscribe(HttpSliceX.handleAwaredFlush());

                return Observable.<InOut>just(new InOut() {
                    @Override
                    public Observable<? extends HttpSlice> inbound() {
                        return orgio.inbound();
                    }

                    @Override
                    public Observable<? extends Object> outbound() {
                        return new HystrixObservableCommand<Object>(HystrixObservableCommand.Setter
                                .withGroupKey(HystrixCommandGroupKey.Factory.asKey("forward"))
                                .andCommandKey(HystrixCommandKey.Factory.asKey(summary + "-response"))
                                .andCommandPropertiesDefaults(HystrixCommandProperties.Setter()
//                                        .withExecutionTimeoutEnabled(false)
                                        .withExecutionTimeoutInMilliseconds(30 * 1000)
                                        .withExecutionIsolationSemaphoreMaxConcurrentRequests(1000)
                                        )
                                ) {
                            @Override
                            protected Observable<Object> construct() {
                                return cached.compose(HttpSliceX.advanceBySended());
                            }
                        }.toObservable();
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

    private Observable<? extends HttpSlice> buildOutbound(
            final HttpTrade trade,
            final Observable<? extends HttpSlice> inbound,
            final Target target,
            final StopWatch stopWatch) {
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
                                    refResp.get());
                        });

                    enableDisposeSended(upstream.writeCtrl(), MAX_RETAINED_SIZE);

                    upstream.writeCtrl().sended().subscribe(HttpSliceX.handleAwaredFlush());

                    return Observable
                            .zip(isDBS().doOnNext(configDBS(trade)), isRBS().doOnNext(configRBS(trade, upstream)),
                                    (dbs, rbs) -> Integer.MIN_VALUE)
                            .flatMap(any -> upstream.defineInteraction(
                                    inbound.map(holdRequestAndProcessKeepAlive(refReq, isKeepAliveFromClient))
                                            .compose(HttpSliceX.advanceBySended())))
                            .map(holdResponseAndProcessKeepAlive(refResp, isKeepAliveFromClient));
                });
    }

    private Func1<HttpSlice, ? extends HttpSlice> holdRequestAndProcessKeepAlive(
            final AtomicReference<HttpRequest> refReq, final AtomicBoolean isKeepAliveFromClient) {
        return slice -> new HttpSlice() {
            @Override
            public Single<Boolean> hasNext() {
                return slice.hasNext();
            }
            @Override
            public Observable<? extends DisposableWrapper<? extends HttpObject>> element() {
                return slice.element().flatMap(RxNettys.splitdwhs())
                        .map(addKeepAliveIfNeeded(refReq, isKeepAliveFromClient));
            }
            @Override
            public Observable<? extends HttpSlice> next() {
                return slice.next();
            }
        };
    }

    private Func1<HttpSlice, ? extends HttpSlice> holdResponseAndProcessKeepAlive(
            final AtomicReference<HttpResponse> refResp, final AtomicBoolean isKeepAliveFromClient) {
        return slice -> new HttpSlice() {

            @Override
            public Single<Boolean> hasNext() {
                return slice.hasNext();
            }

            @Override
            public Observable<? extends DisposableWrapper<? extends HttpObject>> element() {
                return slice.element().flatMap(RxNettys.splitdwhs())
                        .map(removeKeepAliveIfNeeded(refResp, isKeepAliveFromClient));
            }

            @Override
            public Observable<? extends HttpSlice> next() {
                return slice.next();
            }
        };
    }

    private Action1<? super Boolean> configRBS(final HttpTrade trade, final HttpInitiator initiator) {
        return rbs -> {
            LOG.info("forward: pathPattern {}'s rbs {}", _matcher.pathPattern(), rbs);
            if (rbs) {
//                ReadPolicies.enableRBS(trade, initiator.writeCtrl());
//                ReadPolicies.enableRBS(initiator, trade.writeCtrl());
            }
        };
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
                .remoteAddress(buildAddress(target.serviceUri())).feature(target.features().call()).build());
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

    private Observable<Boolean> isRBS() {
        return this._finder.find("configs", Map.class).map(conf -> !istrue(conf.get(_matcher.pathPattern() + ":" + "disable_rbs")));
    }

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

    private <T> Func1<T, T> addKeepAliveIfNeeded(
            final AtomicReference<HttpRequest> refReq,
            final AtomicBoolean isKeepAliveFromClient) {
        return dwh -> {
            if (DisposableWrapperUtil.unwrap(dwh) instanceof HttpRequest) {
                final HttpRequest req = (HttpRequest) DisposableWrapperUtil.unwrap(dwh);
                refReq.set(req);
                // only check first time, bcs inbound could be process many
                // times
                if (isKeepAliveFromClient.get()) {
                    isKeepAliveFromClient.set(HttpUtil.isKeepAlive(req));
                    if (!isKeepAliveFromClient.get()) {
                        // if NOT keep alive, force it
                        final DefaultHttpRequest newreq = new DefaultHttpRequest(req.protocolVersion(), req.method(),
                                req.uri());
                        newreq.headers().add(req.headers());
                        newreq.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                        LOG.info("FORCE-KeepAlive: add Connection header with KeepAlive for incoming req:\n[{}]", req);
                        return (T)RxNettys.wrap4release(newreq);
                    }
                }
            }
            return dwh;
        };
    }

    private <T> Func1<T, T> removeKeepAliveIfNeeded(
            final AtomicReference<HttpResponse> refResp,
            final AtomicBoolean isKeepAliveFromClient) {
        return dwh -> {
            if (DisposableWrapperUtil.unwrap(dwh) instanceof HttpResponse) {
                final HttpResponse resp = (HttpResponse) DisposableWrapperUtil.unwrap(dwh);
                refResp.set(resp);
                if (!isKeepAliveFromClient.get()) {
                    if (HttpUtil.isKeepAlive(resp)) {
                        // if NOT keep alive from client, remove keepalive
                        // header
                        final DefaultHttpResponse newresp = new DefaultHttpResponse(resp.protocolVersion(),
                                resp.status());
                        newresp.headers().add(resp.headers());
                        newresp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                        LOG.info("FORCE-KeepAlive: set Connection header with Close for sendback resp:\n[{}]", resp);
                        return (T)RxNettys.wrap4release(newresp);
                    }
                }
            }
            return dwh;
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
