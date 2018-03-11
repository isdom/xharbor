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
import org.jocean.http.Inbound;
import org.jocean.http.Outbound;
import org.jocean.http.ReadPolicies;
import org.jocean.http.TrafficCounter;
import org.jocean.http.TransportException;
import org.jocean.http.client.HttpClient;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.BeanFinder;
import org.jocean.idiom.DisposableWrapper;
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

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import rx.Observable;
import rx.Single;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;

public class ForwardTrade implements TradeReactor {
    
    private static final int MAX_RETAINED_SIZE = 8 * 1024;
    private static final long _period = 20; // 30 seconds
    private static final Logger LOG = LoggerFactory
            .getLogger(ForwardTrade.class);
    
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
        return io.inbound().map(DisposableWrapperUtil.unwrap()).compose(RxNettys.asHttpRequest()).flatMap(req -> {
            if (null == req) {
                return null;
            } else {
                if (_matcher.match(req)) {
                    final MarkableTargetImpl target = selectTarget();
                    if (null == target) {
                        // no target
                        LOG.warn("NONE_TARGET to forward for trade {}", ctx.trade());
                        return Observable.just(null);
                    } else {
                        return io4forward(ctx, io, target);
                    }
                } else {
                    // not handle this trade
                    return Observable.just(null);
                }
            }
        }).compose(RxObservables.<InOut>ensureSubscribeAtmostOnce()).toSingle();
    }
    
    private Observable<InOut> io4forward(final ReactContext ctx, final InOut originalio, final MarkableTargetImpl target) {
        // outbound 可被重复订阅
        final Observable<? extends DisposableWrapper<HttpObject>> cachedOutbound = 
                buildOutbound(ctx.trade(), originalio.inbound(), target, ctx.watch()).cache();
        
        //  启动转发 (forward)
        return cachedOutbound.first().doOnError(error -> {
            // remember reset to false after a while
            if (isCommunicationFailure(error)) {
                markServiceDownStatus(target, true);
                LOG.warn("COMMUNICATION_FAILURE({}), so mark service [{}] down.",
                        ExceptionUtils.exception2detail(error), target.serviceUri());
                _timer.newTimeout(new TimerTask() {
                    @Override
                    public void run(final Timeout timeout) throws Exception {
                        // reset down flag
                        markServiceDownStatus(target, false);
                        LOG.info(
                                "reset service [{}] down to false, after {} second cause by COMMUNICATION_FAILURE({}).",
                                target.serviceUri(), _period, ExceptionUtils.exception2detail(error));
                    }
                }, _period, TimeUnit.SECONDS);
            }
        }).flatMap(dwh -> {
            if (dwh.unwrap() instanceof HttpResponse) {
                final HttpResponse resp = (HttpResponse) dwh.unwrap();
                if (LOG.isDebugEnabled()) {
                    LOG.debug("recv first response head part {}, so push toNext valid io.", resp);
                }

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
                    _timer.newTimeout(new TimerTask() {
                        @Override
                        public void run(final Timeout timeout) throws Exception {
                            // reset down flag
                            target.markAPIDownStatus(false);
                            LOG.info(
                                    "reset service [{}]'s matched {} APIs down to false, after {} second cause by SERVER_ERROR({}).",
                                    target.serviceUri(), _matcher, _period, resp.status());
                        }
                    }, _period, TimeUnit.SECONDS);
                    return Observable.error(new TransportException("SERVER_ERROR(" + resp.status() + ")"));
                }

                return Observable.<InOut>just(new InOut() {
                    @Override
                    public Observable<? extends DisposableWrapper<HttpObject>> inbound() {
                        return originalio.inbound();
                    }

                    @Override
                    public Observable<? extends DisposableWrapper<HttpObject>> outbound() {
                        return cachedOutbound;
                    }
                });
            } else {
                LOG.warn("first httpobject {} is not HttpResponse, can't use as http response stream", dwh);
                return Observable.error(new RuntimeException("invalid http response"));
            }
        });
    }

//    public interface Sending {
//        public int readableBytes(); 
//    }
    
    private Observable<? extends DisposableWrapper<HttpObject>> buildOutbound(
            final HttpTrade trade, 
            final Observable<? extends DisposableWrapper<HttpObject>> inbound, 
            final Target target,
            final StopWatch stopWatch) {
            
        final AtomicBoolean isKeepAliveFromClient = new AtomicBoolean(true);
        final AtomicReference<HttpRequest> refReq = new AtomicReference<>();
        final AtomicReference<HttpResponse> refResp = new AtomicReference<>();
        
        return isRBS().flatMap(isrbs -> 
            this._finder.find(HttpClient.class).flatMap(client->client.initiator()
                        .remoteAddress(buildAddress(target)).feature(target.features().call()).build())
                .flatMap(initiator -> {
//                    trade.setReadPolicy(ReadPolicies.never());
                    // TBD: using ReadPolicies.ByOutbound
                    // ref:
                    // https://github.com/isdom/xharbor/commit/e81069dd56bfb68b08c971923d24958c438ffe2b#diff-0a4a34cc848464f04687f26d3d122a59L211

                    trade.doOnTerminate(initiator.closer());
                    final TrafficCounter initiatorTraffic = initiator.traffic();

                    trade.doOnTerminate(() -> {
                            final long ttl = stopWatch.stopAndRestart();
                            final RelayMemo memo = _memoBuilder.build(target, buildRoutingInfo(refReq.get()));
                            memo.incBizResult(RESULT.RELAY_SUCCESS, ttl);
                            LOG.info(
                                    "FORWARD_SUCCESS" + "\ncost:[{}]s,forward_to:[{}]"
                                            + "\nFROM:inbound:[{}]bytes,outbound:[{}]bytes"
                                            + "\nTO:upload:[{}]bytes,download:[{}]bytes"
                                            + "\nin-channel:{}\nout-channel:{}" + "\nREQ\n[{}]\nsendback\nRESP\n[{}]",
                                    ttl / (float) 1000.0, target.serviceUri(), trade.traffic().inboundBytes(),
                                    trade.traffic().outboundBytes(), initiatorTraffic.outboundBytes(),
                                    initiatorTraffic.inboundBytes(), trade.transport(), initiator.transport(),
                                    refReq.get(), refResp.get());
                        });

                    final AtomicInteger up_sendingSize = new AtomicInteger(0);
//                    final AtomicInteger up_sendedSize = new AtomicInteger(0);
//                    final AtomicInteger down_sendingSize = new AtomicInteger(0);
//                    final AtomicInteger down_sendedSize = new AtomicInteger(0);
                    
                    initiator.writeCtrl().sended().subscribe(sended -> {
                        if (up_sendingSize.get() > MAX_RETAINED_SIZE) {
                            if (LOG.isTraceEnabled()) {
                                LOG.trace("sendingSize is {}, dispose sended {}", up_sendingSize.get(),
                                        sended);
                            }
                            DisposableWrapperUtil.dispose(sended);
                        } else {
                            if (LOG.isTraceEnabled()) {
                                LOG.trace("sendingSize is {}, SKIP sended {}", up_sendingSize.get(), sended);
                            }
                        }
                    });
                    
                    // 对已经发送成功的 DisposableWrapper<?>，及时 invoke it's dispose() 回收占用的资源 (memory, ...)
                    trade.writeCtrl().sended().subscribe(sended -> DisposableWrapperUtil.dispose(sended));
                    
                    initiator.writeCtrl().setFlushPerWrite(true);
                    trade.writeCtrl().setFlushPerWrite(true);
                    
//                    initiator.writeCtrl().sended().subscribe(accumulateSended(up_sendedSize));
//                    trade.writeCtrl().sended().subscribe(accumulateSended(down_sendedSize));
                    
                    final AtomicInteger up_sendingCount = new AtomicInteger(0);
                    final AtomicInteger down_sendingCount = new AtomicInteger(0);
                    
                    if (isrbs) {
//                        final RecvBySend down_rbs = new RecvBySend();
//                        
//                        down_rbs.enableRBS(initiator, trade);
//                        
//                        final RecvBySend up_rbs = new RecvBySend();
//                        
//                        up_rbs.enableRBS(trade, initiator);
                        
                        
                        final AtomicInteger up_sendedCount = new AtomicInteger(0);
                        final AtomicInteger down_sendedCount = new AtomicInteger(0);
                        
                        initiator.writeCtrl().sended().subscribe(incCounter(up_sendedCount));
                        trade.writeCtrl().sended().subscribe(incCounter(down_sendedCount));
                        
                        trade.setReadPolicy(ReadPolicies.bysended(initiator.writeCtrl(), 
                                () -> up_sendingCount.get() - up_sendedCount.get(), 0));
                        
                        initiator.setReadPolicy(ReadPolicies.bysended(trade.writeCtrl(), 
                                () -> down_sendingCount.get() - down_sendedCount.get(), 0));
                    }
                    
                    return initiator.defineInteraction(inbound.flatMap(RxNettys.splitdwhs())
                                .map(addKeepAliveIfNeeded(refReq, isKeepAliveFromClient))
                                .doOnNext(incCounter(up_sendingCount))
//                                .map(accumulateAndMixinSending(up_sendingSize)))
                            .flatMap(RxNettys.splitdwhs())
                            .map(removeKeepAliveIfNeeded(refResp, isKeepAliveFromClient))
                            .doOnNext(incCounter(down_sendingCount))
//                            .map(accumulateAndMixinSending(down_sendingSize)
                            );
                    })
                );
    }
    
    static class RecvBySend {
        final AtomicInteger _sendingCount = new AtomicInteger(0);
        final AtomicInteger _sendedCount = new AtomicInteger(0);
        
        public Action1<Object> incSendingCount() {
            return obj -> _sendingCount.incrementAndGet();
        }
        
        public void enableRBS(final Inbound inbound, final Outbound outbound) {
            outbound.writeCtrl().sended().subscribe(incSendedCount());
            inbound.setReadPolicy(ReadPolicies.bysended(outbound.writeCtrl(), 
                    () -> _sendingCount.get() - _sendedCount.get(), 0));
        }
        
        private Action1<? super Object> incSendedCount() {
            return sended -> _sendedCount.incrementAndGet();
        }
    }

    private Observable<Boolean> isRBS() {
        return this._finder.find("configs", Map.class).map(conf -> conf.get(_matcher.pathPattern() + ":" + "rbs") != null);
    }

//  private int getReadableBytes(final HttpObject hobj) {
//  return hobj instanceof HttpContent ? ((HttpContent) hobj).content().readableBytes() : 0;
//}

//    private Func1<? super DisposableWrapper<HttpObject>, ? extends DisposableWrapper<HttpObject>> accumulateAndMixinSending(
//            final AtomicInteger sendingSize) {
//        return dwh -> {
//            final HttpObject hobj = dwh.unwrap();
//            final int readableBytes = getReadableBytes(hobj);
//            sendingSize.addAndGet(readableBytes);
//            return Proxys.mixin().mix(DisposableWrapper.class, dwh)
//                    .mix(Sending.class, ()->readableBytes).build();
//        };
//    }
    
//  private Action1<? super Object> accumulateSended(final AtomicInteger sendedSize) {
//  return sended -> sendedSize.addAndGet(((Sending)sended).readableBytes());
//}

    private Action1<Object> incCounter(final AtomicInteger counter) {
        return obj -> counter.incrementAndGet();
    }
    
    private InetSocketAddress buildAddress(final Target target) {
        return new InetSocketAddress(
            target.serviceUri().getHost(), 
            target.serviceUri().getPort());
    }

    private Func1<DisposableWrapper<HttpObject>, DisposableWrapper<HttpObject>> addKeepAliveIfNeeded(
            final AtomicReference<HttpRequest> refReq, 
            final AtomicBoolean isKeepAliveFromClient) {
        return dwh -> {
            if (dwh.unwrap() instanceof HttpRequest) {
                final HttpRequest req = (HttpRequest) dwh.unwrap();
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
                        return RxNettys.wrap4release(newreq);
                    }
                }
            }
            return dwh;
        };
    }
    
    private Func1<DisposableWrapper<HttpObject>, DisposableWrapper<HttpObject>> removeKeepAliveIfNeeded(
            final AtomicReference<HttpResponse> refResp,
            final AtomicBoolean isKeepAliveFromClient) {
        return dwh -> {
            if (dwh.unwrap() instanceof HttpResponse) {
                final HttpResponse resp = (HttpResponse) dwh.unwrap();
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
                        return RxNettys.wrap4release(newresp);
                    }
                }
            }
            return dwh;
        };
    }

    private MarkableTargetImpl selectTarget() {
        int total = 0;
        MarkableTargetImpl best = null;
        for ( MarkableTargetImpl peer : this._targets ) {
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
    
//    private Action1<Object> unholdInboundMessage(final HttpMessageHolder holder) {
//        return new Action1<Object>() {
//            @Override
//            public void call(final Object msg) {
//                if (msg instanceof HttpContent) {
//                    if (holder.isFragmented()
//                        || holder.retainedByteBufSize() > MAX_RETAINED_SIZE) {
//                        LOG.info("holder({}) BEGIN_RELEASE msg({}), now it's retained size: {}",
//                                holder, msg, holder.retainedByteBufSize());
//                        holder.releaseHttpContent((HttpContent)msg);
//                        LOG.info("holder({}) ENDOF_RELEASE msg({}), now it's retained size: {}",
//                                holder, msg, holder.retainedByteBufSize());
//                    }
//                }
//            }};
//    }

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
