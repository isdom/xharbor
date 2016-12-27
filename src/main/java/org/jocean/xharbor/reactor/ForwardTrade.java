package org.jocean.xharbor.reactor;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.Feature;
import org.jocean.http.client.HttpClient;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.RxNettys;
import org.jocean.xharbor.api.MarkableTarget;
import org.jocean.xharbor.api.Target;
import org.jocean.xharbor.api.TradeReactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import rx.Observable;
import rx.Single;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;

public class ForwardTrade implements TradeReactor {
    
    private static final Logger LOG = LoggerFactory
            .getLogger(ForwardTrade.class);
    
    public ForwardTrade(
            final MatchRule  matcher,
            final HttpClient httpclient
            ) {
        this._matcher = matcher;
        this._httpclient = httpclient;
    }
    
    public void addTarget(final Target target) {
        this._targets.add(new MarkableTargetImpl(target));
    }
    
    @Override
    public Single<? extends InOut> react(final HttpTrade trade, final InOut io) {
        if (null != io.outbound()) {
            return Single.<InOut>just(null);
        }
        return io.inbound().compose(RxNettys.asHttpRequest())
                .map(new Func1<HttpRequest, InOut>() {
                    @Override
                    public InOut call(final HttpRequest req) {
                        if (null == req) {
                            return null;
                        } else {
                            if ( _matcher.match(req) ) {
                                final Target target = selectTarget();
                                if (null == target) {
                                    //  no target
                                    LOG.warn("no target to forward for trade {}", trade);
                                    return null;
                                } else {
                                    return io4forward(io, target);
                                }
                            } else {
                                //  not handle this trade
                                return null;
                            }
                        }
                    }})
                .toSingle();
    }
    
    private InOut io4forward(final InOut originalio, final Target target) {
        return new InOut() {
            @Override
            public Observable<? extends HttpObject> inbound() {
                return originalio.inbound();
            }
            @Override
            public Observable<? extends HttpObject> outbound() {
                final AtomicReference<HttpRequest> ref = new AtomicReference<>();
                return _httpclient.defineInteraction(
                            new InetSocketAddress(
                                target.serviceUri().getHost(), 
                                target.serviceUri().getPort()), 
                            originalio.inbound().doOnNext(new Action1<HttpObject>() {
                                @Override
                                public void call(final HttpObject httpobj) {
                                    if (httpobj instanceof HttpRequest) {
                                        ref.set((HttpRequest)httpobj);
                                    }
                                }}),
                            target.features().call())
                        .doOnNext(new Action1<HttpObject>() {
                            @Override
                            public void call(final HttpObject httpobj) {
                                if (httpobj instanceof HttpResponse) {
                                    final HttpResponse resp = (HttpResponse)httpobj;
                                    LOG.info("FORWARD relay \nREQ\n[{}]\n and sendback \nRESP\n[{}]", 
                                            ref.get(), resp);
                                }
                            }});
            }};
    }
    
    private MarkableTarget selectTarget() {
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
    
    private boolean isTargetActive(final MarkableTargetImpl peer) {
//        return !(this._serviceMemo.isServiceDown(peer._target.serviceUri()) || peer._down.get());
        return !peer._down.get();
    }
    
    private class MarkableTargetImpl implements MarkableTarget {
        
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
        
        @Override
        public int addWeight(final int deltaWeight) {
            int weight = this._effectiveWeight.addAndGet(deltaWeight);
            if ( weight > MAX_EFFECTIVEWEIGHT ) {
                weight = this._effectiveWeight.addAndGet(-deltaWeight);
            }
            return weight;
        }
        
        @Override
        public void markAPIDownStatus(final boolean isDown) {
            this._down.set(isDown);
        }
        
        @Override
        public void markServiceDownStatus(final boolean isDown) {
//            _serviceMemo.markServiceDownStatus(this._target.serviceUri(), isDown);
        }
        
        private final Target _target;
        private final AtomicInteger _currentWeight = new AtomicInteger(1);
        private final AtomicInteger _effectiveWeight = new AtomicInteger(1);
        private final AtomicBoolean _down = new AtomicBoolean(false);
    }
    
    private final HttpClient    _httpclient;
    private final MatchRule     _matcher;
    private final List<MarkableTargetImpl>  _targets = 
            Lists.newCopyOnWriteArrayList();
}
