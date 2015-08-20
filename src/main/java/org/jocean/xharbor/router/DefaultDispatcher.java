/**
 * 
 */
package org.jocean.xharbor.router;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.http.Feature;
import org.jocean.http.client.HttpClient;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.StopWatch;
import org.jocean.idiom.rx.RxObservables;
import org.jocean.idiom.stats.BizMemo;
import org.jocean.idiom.stats.BizMemo.StepMemo;
import org.jocean.xharbor.api.Dispatcher;
import org.jocean.xharbor.api.MarkableTarget;
import org.jocean.xharbor.api.RelayMemo;
import org.jocean.xharbor.api.RelayMemo.RESULT;
import org.jocean.xharbor.api.RelayMemo.STEP;
import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.api.RoutingInfoMemo;
import org.jocean.xharbor.api.ServiceMemo;
import org.jocean.xharbor.api.Target;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;

/**
 * @author isdom
 *
 */
public class DefaultDispatcher implements Dispatcher {
    
    private static final Logger LOG = LoggerFactory
            .getLogger(DefaultDispatcher.class);

    private static final int MAX_EFFECTIVEWEIGHT = 1000;
    
    public DefaultDispatcher(
            final Target[] targets, 
            final Action1<HttpRequest> rewriteRequest, 
            final Action1<HttpResponse> rewriteResponse, 
            final Func1<HttpRequest, Boolean> authorization, 
            final Func1<HttpRequest, FullHttpResponse> responser,
            final ServiceMemo serviceMemo, 
            final HttpClient httpClient,
            final RelayMemo.Builder memoBuilder,
            final RoutingInfoMemo noRoutingMemo
            ) {
        this._noRoutingMemo = noRoutingMemo;
        this._memoBuilder = memoBuilder;
        this._httpClient = httpClient;
        this._serviceMemo = serviceMemo;
        this._rewriteRequest = rewriteRequest;
        this._rewriteResponse = rewriteResponse;
        this._authorization = authorization;
        this._responser = responser;
        this._targets = new ArrayList<MarkableTargetImpl>() {
            private static final long serialVersionUID = 1L;
        {
            for ( Target target : targets) {
                this.add(new MarkableTargetImpl(target));
            }
        }}.toArray(new MarkableTargetImpl[0]);
    }
    
    @Override
    public String toString() {
        return Arrays.toString( new ArrayList<String>() {
            private static final long serialVersionUID = 1L;
        {
            this.add("rewriteRequest:" + _rewriteRequest.toString());
            this.add("authorize:" + _authorization.toString());
            for (MarkableTargetImpl peer : _targets) {
                this.add(peer._target.toString() + ":active(" + isTargetActive(peer)
                        + "):effectiveWeight(" + peer._effectiveWeight.get()
                        + "):currentWeight(" + peer._currentWeight.get()
                        + ")"
                        );
            }
        }}.toArray(new String[0]) );
    }

    private MarkableTarget dispatch() {
        int total = 0;
        MarkableTargetImpl best = null;
        for ( MarkableTargetImpl peer : this._targets ) {
            if ( isTargetActive(peer) ) {
                // peer->current_weight += peer->effective_weight; 
                final int effectiveWeight = peer._effectiveWeight.get();
                final int currentWeight = peer._currentWeight.addAndGet( effectiveWeight );
                total += effectiveWeight;
                
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
        
//        best->current_weight -= total;
        best._currentWeight.addAndGet(-total);
        
        return best;
    }
    
    @Override
    public boolean IsValid() {
        return this._targets.length > 0;
    }

    /**
     * @param peer
     * @return
     */
    private boolean isTargetActive(final MarkableTargetImpl peer) {
        return !(this._serviceMemo.isServiceDown(peer._target.serviceUri()) || peer._down.get());
    }
    
    private class MarkableTargetImpl implements MarkableTarget {
        
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
            _serviceMemo.markServiceDownStatus(this._target.serviceUri(), isDown);
        }
        
        private final Target _target;
        private final AtomicInteger _currentWeight = new AtomicInteger(1);
        private final AtomicInteger _effectiveWeight = new AtomicInteger(1);
        private final AtomicBoolean _down = new AtomicBoolean(false);
    }
    
    private final ServiceMemo _serviceMemo;
    private final Action1<HttpRequest> _rewriteRequest;
    private final Action1<HttpResponse> _rewriteResponse;
    private final Func1<HttpRequest, Boolean> _authorization;
    private final Func1<HttpRequest, FullHttpResponse> _responser;
    private final MarkableTargetImpl[] _targets;

    private FullHttpResponse tryResponse(final HttpRequest httpRequest) {
        return null!=_responser ? _responser.call(httpRequest) : null;
    }
    
    private boolean isNeedAuthorization(final HttpRequest httpRequest) {
        return _authorization.call(httpRequest);
    }
    
    private void rewriteRequest(final HttpRequest request) {
        _rewriteRequest.call(request);
    }
    
    private void rewriteResponse(final HttpResponse response) {
        _rewriteResponse.call(response);
    }
    
    @Override
    public Observable<HttpObject> response(
            final RoutingInfo info,
            final HttpRequest request, 
            final Observable<HttpObject> fullRequest) {
        
        final MarkableTarget target = dispatch();
        if (null==target) {
            LOG.warn("can't found matched target service for request:[{}]\njust return 200 OK.", 
                    request);
            _noRoutingMemo.incRoutingInfo(info);
            //   TODO, mark this status
    //        setEndReason("relay.NOROUTING");
            return RxObservables.delaySubscriptionUntilCompleted(
                    RxNettys.response200OK(request.getProtocolVersion()),
                    fullRequest);
        }
        
        final RelayMemo memo = _memoBuilder.build(target, info);
        final StopWatch watch4Result = new StopWatch();
        
        final FullHttpResponse shortResponse = 
                tryResponse(request);
        if (null != shortResponse) {
            return RxObservables.delaySubscriptionUntilCompleted(
                    Observable.<HttpObject>just(shortResponse),
                    fullRequest);
        } else if (isNeedAuthorization(request)) {
            return RxObservables.delaySubscriptionUntilCompleted(
                    RxNettys.response401Unauthorized(
                            request.getProtocolVersion(), 
                            "Basic realm=\"iplusmed\"")
                        .doOnCompleted(new Action0() {
                            @Override
                            public void call() {
                                memo.incBizResult(RESULT.HTTP_UNAUTHORIZED, 
                                        watch4Result.stopAndRestart());
                            }}),
                    fullRequest);
        } else {
            final StepMemo<STEP> stepmemo = 
                BizMemo.Util.buildStepMemo(memo, new StopWatch());
            stepmemo.beginBizStep(STEP.ROUTING);
            
            rewriteRequest(request);
            
            return _httpClient.defineInteraction(
                    new InetSocketAddress(
                        target.serviceUri().getHost(), 
                        target.serviceUri().getPort()), 
                    fullRequest
                    .doOnNext(new Action1<HttpObject>() {
                        @Override
                        public void call(final HttpObject httpObj) {
                            if (httpObj instanceof HttpRequest) {
                                stepmemo.beginBizStep(STEP.TRANSFER_CONTENT);
                            }
                        }}),
                    target.features().call())
                    .compose(RxNettys.objects2httpobjs())
                    .doOnNext(new Action1<HttpObject>() {
                        @Override
                        public void call(final HttpObject httpObj) {
                            if (httpObj instanceof HttpResponse) {
                                stepmemo.beginBizStep(STEP.RECV_RESP);
                                rewriteResponse((HttpResponse)httpObj);
                            }
                        }})
                    .doOnError(new Action1<Throwable>() {
                        @Override
                        public void call(final Throwable e) {
                            stepmemo.endBizStep();
                            LOG.error("exception when transfer, detail: {}",
                                    ExceptionUtils.exception2detail(e));
                            memo.incBizResult(RESULT.RELAY_FAILURE, watch4Result.stopAndRestart());
                        }})
                    .doOnCompleted(new Action0() {
                        @Override
                        public void call() {
                            stepmemo.endBizStep();
                            memo.incBizResult(RESULT.RELAY_SUCCESS, watch4Result.stopAndRestart());
                        }});
        }
    }

    private final HttpClient _httpClient;
    private final RelayMemo.Builder _memoBuilder;
    private final RoutingInfoMemo _noRoutingMemo;
}
