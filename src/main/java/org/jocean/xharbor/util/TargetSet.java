/**
 * 
 */
package org.jocean.xharbor.util;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.xharbor.api.Dispatcher;
import org.jocean.xharbor.api.ServiceMemo;
import org.jocean.xharbor.api.Target;

import rx.functions.Action1;
import rx.functions.Func1;

/**
 * @author isdom
 *
 */
public class TargetSet implements Dispatcher {

    private static final int MAX_EFFECTIVEWEIGHT = 1000;
    
    public TargetSet(
            final URI[] uris, 
            final boolean isCheckResponseStatus, 
            final Action1<HttpRequest> rewriteRequest, 
            final Action1<HttpResponse> rewriteResponse, 
            final Func1<HttpRequest, Boolean> needAuthorization, 
            final Func1<HttpRequest, FullHttpResponse> shortResponse,
            final ServiceMemo serviceMemo            ) {
        this._serviceMemo = serviceMemo;
        this._isCheckResponseStatus = isCheckResponseStatus;
        this._rewriteRequest = rewriteRequest;
        this._rewriteResponse = rewriteResponse;
        this._needAuthorization = needAuthorization;
        this._shortResponse = shortResponse;
        this._targets = new ArrayList<TargetImpl>() {
            private static final long serialVersionUID = 1L;
        {
            for ( URI uri : uris) {
                this.add(new TargetImpl(uri));
            }
        }}.toArray(new TargetImpl[0]);
    }
    
    @Override
    public String toString() {
        return Arrays.toString( new ArrayList<String>() {
            private static final long serialVersionUID = 1L;
        {
            this.add("rewriteRequest:" + _rewriteRequest.toString());
            this.add("authorize:" + _needAuthorization.toString());
            for (TargetImpl peer : _targets) {
                this.add(peer._uri.toString() + ":down(" + isTargetDown(peer)
                        + "):effectiveWeight(" + peer._effectiveWeight.get()
                        + "):currentWeight(" + peer._currentWeight.get()
                        + ")"
                        );
            }
        }}.toArray(new String[0]) );
    }

    @Override
    public Target dispatch() {
        int total = 0;
        TargetImpl best = null;
        for ( TargetImpl peer : this._targets ) {
            if ( !isTargetDown(peer) ) {
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
    private boolean isTargetDown(final TargetImpl peer) {
        return this._serviceMemo.isServiceDown(peer._uri) || peer._down.get();
    }
    
    private class TargetImpl implements Target {
        
        @Override
        public URI serviceUri() {
            return this._uri;
        }
        
        @Override
        public void rewriteRequest(final HttpRequest request) {
            _rewriteRequest.call(request);
        }
        
        @Override
        public void rewriteResponse(final HttpResponse response) {
            _rewriteResponse.call(response);
        }
        
        @Override
        public boolean isNeedAuthorization(final HttpRequest httpRequest) {
            return _needAuthorization.call(httpRequest);
        }
        
        @Override
        public boolean isCheckResponseStatus() {
            return _isCheckResponseStatus;
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
            _serviceMemo.markServiceDownStatus(this._uri, isDown);
        }
        
        @Override
        public FullHttpResponse needShortResponse(final HttpRequest httpRequest) {
            return null!=_shortResponse ? _shortResponse.call(httpRequest) : null;
        }
        
        TargetImpl(final URI uri) {
            this._uri = uri;
        }
        
        private final URI _uri;
        private final AtomicInteger _currentWeight = new AtomicInteger(1);
        private final AtomicInteger _effectiveWeight = new AtomicInteger(1);
        private final AtomicBoolean _down = new AtomicBoolean(false);
    }
    
    private final ServiceMemo _serviceMemo;
    private final boolean _isCheckResponseStatus;
    private final Action1<HttpRequest> _rewriteRequest;
    private final Action1<HttpResponse> _rewriteResponse;
    private final Func1<HttpRequest, Boolean> _needAuthorization;
    private final Func1<HttpRequest, FullHttpResponse> _shortResponse;
    private final TargetImpl[] _targets;
}
