package org.jocean.xharbor.reactor;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.Feature;
import org.jocean.http.TransportException;
import org.jocean.http.client.HttpClient;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.HttpMessageHolder;
import org.jocean.http.util.HttpUtil.TrafficCounterFeature;
import org.jocean.http.util.Nettys.ChannelAware;
import org.jocean.http.util.RxNettys;
import org.jocean.http.util.SendedMessageAware;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.StopWatch;
import org.jocean.idiom.rx.RxActions;
import org.jocean.idiom.rx.RxObservables;
import org.jocean.idiom.rx.RxSubscribers;
import org.jocean.xharbor.api.RelayMemo;
import org.jocean.xharbor.api.RelayMemo.RESULT;
import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.api.ServiceMemo;
import org.jocean.xharbor.api.Target;
import org.jocean.xharbor.api.TradeReactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

import io.netty.channel.Channel;
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
            final HttpClient httpclient,
            final RelayMemo.Builder memoBuilder, 
            final ServiceMemo serviceMemo, 
            final Timer timer
            ) {
        this._matcher = matcher;
        this._httpclient = httpclient;
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
        return io.inbound().compose(RxNettys.asHttpRequest())
                .flatMap(new Func1<HttpRequest, Observable<InOut>>() {
                    @Override
                    public Observable<InOut> call(final HttpRequest req) {
                        if (null == req) {
                            return null;
                        } else {
                            if ( _matcher.match(req) ) {
                                final MarkableTargetImpl target = selectTarget();
                                if (null == target) {
                                    //  no target
                                    LOG.warn("NONE_TARGET to forward for trade {}", ctx.trade());
                                    return Observable.just(null);
                                } else {
                                    return io4forward(ctx, io, target);
                                }
                            } else {
                                //  not handle this trade
                                return Observable.just(null);
                            }
                        }
                    }})
                .compose(RxObservables.<InOut>ensureSubscribeAtmostOnce())
                .toSingle();
    }
    
    private Observable<InOut> io4forward(final ReactContext ctx, final InOut originalio, final MarkableTargetImpl target) {
        final Observable<? extends HttpObject> outbound = 
                buildOutbound(ctx.trade(), originalio.inbound(), target, ctx.watch());
        
        //  outbound 可被重复订阅
        final Observable<? extends HttpObject> cachedOutbound = outbound.cache()
            .compose(RxNettys.duplicateHttpContent())
            ;
        
        //  启动转发 (forward)
        return cachedOutbound.first()
            .doOnError(new Action1<Throwable>() {
                @Override
                public void call(final Throwable error) {
                    //  remember reset to false after a while
                    if (isCommunicationFailure(error)) {
                        markServiceDownStatus(target, true);
                        LOG.warn("COMMUNICATION_FAILURE({}), so mark service [{}] down.",
                                ExceptionUtils.exception2detail(error), target.serviceUri());
                        _timer.newTimeout(new TimerTask() {
                            @Override
                            public void run(final Timeout timeout) throws Exception {
                                // reset down flag
                                markServiceDownStatus(target, false);
                                LOG.info("reset service [{}] down to false, after {} second cause by COMMUNICATION_FAILURE({}).",
                                        target.serviceUri(), _period, ExceptionUtils.exception2detail(error));
                            }
                        },  _period, TimeUnit.SECONDS);
                    }
                    
                }})
            .flatMap(new Func1<HttpObject, Observable<InOut>>() {
                @Override
                public Observable<InOut> call(final HttpObject httpobj) {
                    if (httpobj instanceof HttpResponse) {
                        final HttpResponse resp = (HttpResponse)httpobj;
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("recv first response hear part {}, so push toNext valid io.", resp);
                        }
                        
                        //  404 Not Found
                        if (resp.status().equals(HttpResponseStatus.NOT_FOUND)) {
                            //  Request-URI not found in target service, so try next matched forward target
                            LOG.info("API_NOT_SUPPORTED for target {}, so forward trade({}) to next reactor", target, ctx.trade());
                            return Observable.<InOut>just(null);
                        }
                        
                        //  5XX Server Internal Error
                        if (resp.status().code() >= 500) {
                            //  Server Internal Error 
                            target.markAPIDownStatus(true);
                            LOG.warn("SERVER_ERROR({}), so mark service [{}]'s matched {} APIs down.",
                                    resp.status(), target.serviceUri(), _matcher);
                            _timer.newTimeout(new TimerTask() {
                                @Override
                                public void run(final Timeout timeout) throws Exception {
                                    // reset down flag
                                    target.markAPIDownStatus(false);
                                    LOG.info("reset service [{}]'s matched {} APIs down to false, after {} second cause by SERVER_ERROR({}).",
                                            target.serviceUri(), _matcher, _period, resp.status());
                                }
                            },  _period, TimeUnit.SECONDS);
                            return Observable.error(new TransportException("SERVER_ERROR(" + resp.status() + ")"));
                        }
                        
                        return Observable.<InOut>just(new InOut() {
                            @Override
                            public Observable<? extends HttpObject> inbound() {
                                return originalio.inbound();
                            }
                            @Override
                            public Observable<? extends HttpObject> outbound() {
                                return cachedOutbound;
                            }});
                    } else {
                        LOG.warn("first httpobject {} is not HttpResponse, can't use as http response stream", httpobj);
                        return Observable.error(new RuntimeException("invalid http response"));
                    }
            }});
    }

    private Observable<? extends HttpObject> buildOutbound(
            final HttpTrade trade, 
            final Observable<? extends HttpObject> inbound, 
            final Target target,
            final StopWatch stopWatch) {
        final class ChannelHolder extends Feature.AbstractFeature0 
            implements ChannelAware {
            @Override
            public void setChannel(final Channel channel) {
                _channel = channel;
            }
            private Channel _channel;
        }
        final ChannelHolder channelHolder = new ChannelHolder();
        final Func1<Channel, HttpMessageHolder> holderFactory = new Func1<Channel, HttpMessageHolder>() {
            @Override
            public HttpMessageHolder call(final Channel channel) {
                //  -1 means disable assemble piece to a big block feature
                final HttpMessageHolder holder = new HttpMessageHolder(-1);
                trade.addCloseHook(RxActions.<HttpTrade>toAction1(holder.release()));
                return holder;
            }};
            
        final class ReleaseSendedMessage extends Feature.AbstractFeature0 
            implements SendedMessageAware {
            @Override
            public void setSendedMessage(final Observable<? extends Object> sendedMessage) {
                sendedMessage.subscribe(new Action1<Object>() {
                    @Override
                    public void call(final Object msg) {
                        LOG.info("setInboundAutoRead ON for msg: {} sended", msg);
                        trade.setInboundAutoRead(true);
                        if (msg instanceof HttpContent) {
                            if (trade.inboundHolder().isFragmented()
                                || trade.retainedInboundMemory() > MAX_RETAINED_SIZE) {
                                LOG.info("trade({})'s inboundHolder BEGIN_RELEASE msg({}), now it's retained size: {}",
                                        trade, msg, trade.retainedInboundMemory());
                                trade.inboundHolder().releaseHttpContent((HttpContent)msg);
                                LOG.info("trade({})'s inboundHolder ENDOF_RELEASE msg({}), now it's retained size: {}",
                                        trade, msg, trade.retainedInboundMemory());
                            }
                        }
                    }},
                    RxSubscribers.ignoreError());
            }
        }
            
        final AtomicBoolean isKeepAliveFromClient = new AtomicBoolean(true);
        final AtomicReference<HttpRequest> refReq = new AtomicReference<>();
        final AtomicReference<HttpResponse> refResp = new AtomicReference<>();
        final TrafficCounterFeature trafficCounter = 
                org.jocean.http.util.HttpUtil.buildTrafficCounterFeature();
        
        final Observable<? extends HttpObject> outbound = 
            this._httpclient.interaction()
                .remoteAddress(buildAddress(target))
                .request(buildRequest(trade, inbound, refReq, isKeepAliveFromClient))
                .feature(target.features().call())
                .feature(org.jocean.http.util.HttpUtil.buildHoldMessageFeature(holderFactory))
                .feature(new ReleaseSendedMessage())
                .feature(channelHolder)
                .feature(Feature.FLUSH_PER_WRITE)
                .feature(trafficCounter)
                .build()
                .flatMap(RxNettys.splitFullHttpMessage())
                .map(removeKeepAliveIfNeeded(refResp, isKeepAliveFromClient));
        trade.addCloseHook(new Action1<HttpTrade>() {
            @Override
            public void call(HttpTrade t) {
                final long ttl = stopWatch.stopAndRestart();
                final RelayMemo memo = _memoBuilder.build(target, buildRoutingInfo(refReq.get()));
                memo.incBizResult(RESULT.RELAY_SUCCESS, ttl);
                LOG.info("FORWARD_SUCCESS"
                        + "\ncost:[{}]s,forward_to:[{}]"
                        + "\nFROM:inbound:[{}]bytes,outbound:[{}]bytes"
                        + "\nTO:upload:[{}]bytes,download:[{}]bytes"
                        + "\nin-channel:{}\nout-channel:{}"
                        + "\nREQ\n[{}]\nsendback\nRESP\n[{}]",
                        ttl / (float)1000.0, 
                        target.serviceUri(), 
                        trade.trafficCounter().inboundBytes(),
                        trade.trafficCounter().outboundBytes(),
                        trafficCounter.outboundBytes(),
                        trafficCounter.inboundBytes(),
                        trade.transport(),
                        channelHolder._channel,
                        refReq.get(), 
                        refResp.get());
            }});
        return outbound;
    }

    private InetSocketAddress buildAddress(final Target target) {
        return new InetSocketAddress(
            target.serviceUri().getHost(), 
            target.serviceUri().getPort());
    }

    private Func1<HttpObject, HttpObject> removeKeepAliveIfNeeded(
            final AtomicReference<HttpResponse> refResp,
            final AtomicBoolean isKeepAliveFromClient) {
        return new Func1<HttpObject, HttpObject>() {
                @Override
                public HttpObject call(final HttpObject httpobj) {
                    if (httpobj instanceof HttpResponse) {
                        final HttpResponse resp = (HttpResponse)httpobj;
                        refResp.set(resp);
                        if (!isKeepAliveFromClient.get()) {
                            if (HttpUtil.isKeepAlive(resp)) {
                                // if NOT keep alive from client, remove keepalive header
                                final DefaultHttpResponse newresp = new DefaultHttpResponse(
                                        resp.protocolVersion(), 
                                        resp.status());
                                newresp.headers().add(resp.headers());
                                newresp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                                LOG.info("FORCE-KeepAlive: set Connection header with Close for sendback resp:\n[{}]", resp);
                                return newresp;
                            }
                        }
                    }
                    return httpobj;
                }};
    }

    private Observable<HttpObject> buildRequest(
            final HttpTrade trade, 
            final Observable<? extends HttpObject> inbound,
            final AtomicReference<HttpRequest> refReq,
            final AtomicBoolean isKeepAliveFromClient) {
        return inbound.flatMap(RxNettys.splitFullHttpMessage())
            .map(new Func1<HttpObject, HttpObject>() {
                @Override
                public HttpObject call(final HttpObject httpobj) {
                    if (httpobj instanceof HttpRequest) {
                        final HttpRequest req = (HttpRequest)httpobj;
                        refReq.set(req);
                        //  only check first time, bcs inbound could be process many times
                        if (isKeepAliveFromClient.get()) {
                            isKeepAliveFromClient.set(HttpUtil.isKeepAlive(req));
                            if (!isKeepAliveFromClient.get()) {
                                // if NOT keep alive, force it
                                final DefaultHttpRequest newreq = new DefaultHttpRequest(
                                        req.protocolVersion(), 
                                        req.method(),
                                        req.uri());
                                newreq.headers().add(req.headers());
                                newreq.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                                LOG.info("FORCE-KeepAlive: add Connection header with KeepAlive for incoming req:\n[{}]", req);
                                return newreq;
                            }
                        }
                    }
                    return httpobj;
                }})
            .doOnNext(new Action1<HttpObject>() {
                @Override
                public void call(final HttpObject httpobj) {
                    LOG.info("setInboundAutoRead OFF for msg: {} recvd", httpobj);
                    trade.setInboundAutoRead(false);
                }})
            ;
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
    
    private final HttpClient    _httpclient;
    private final RelayMemo.Builder _memoBuilder;
    private final ServiceMemo   _serviceMemo;
    private final Timer         _timer;
}
