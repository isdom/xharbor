/**
 * 
 */
package org.jocean.xharbor.relay;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jocean.http.TransportException;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.xharbor.api.Dispatcher;
import org.jocean.xharbor.api.Dispatcher.ResponseCtx;
import org.jocean.xharbor.api.RelayMemo.RESULT;
import org.jocean.xharbor.api.Router;
import org.jocean.xharbor.api.RoutingInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Action1;
import rx.functions.Func1;

/**
 * @author isdom
 *
 */
public class RelaySubscriber extends Subscriber<HttpTrade> {

    private static class RouterCtxImpl implements Router.Context {
        private final HashMap<String, Object> _map = new HashMap<String, Object>();
        
        @Override
        public <V> RouterCtxImpl setProperty(final String key, final V obj) {
            _map.put(key, obj);
            return this;
        }
        
        @SuppressWarnings("unchecked")
        @Override
        public <V> V getProperty(String key) {
            return (V)_map.get(key);
        }
        
        @Override
        public Map<String, Object> getProperties() {
            return _map;
        }
        
        public void clear() {
            _map.clear();
        }
    }
    
    private static final Logger LOG =
            LoggerFactory.getLogger(RelaySubscriber.class);

    public RelaySubscriber(final Router<HttpRequest, Dispatcher> router) {
        this._router = router;
    }
    
    @Override
    public void onCompleted() {
        LOG.warn("RelaySubscriber {} onCompleted", this);
    }

    @Override
    public void onError(final Throwable e) {
        LOG.warn("RelaySubscriber {} onError, detail:{}", 
                this, ExceptionUtils.exception2detail(e));
    }

    @Override
    public void onNext(final HttpTrade trade) {
        @SuppressWarnings("unchecked")
        final Observable<HttpObject> cached = (Observable<HttpObject>) trade.inbound();
            
        cached.subscribe(new RequestSubscriber(trade, cached));
    }
    
    private static Observable<HttpObject> buildHttpResponse(
            final Dispatcher dispatcher, 
            final Object transport,
            final HttpRequest request,
            final Observable<? extends HttpObject> fullRequest,
            final RoutingInfo info,
            final AtomicBoolean canRetry) {
        final ResponseCtx ctx = new ResponseCtx(transport);
        return dispatcher.response(ctx, info, request, fullRequest)
            .onErrorResumeNext(new Func1<Throwable, Observable<HttpObject>>() {
                @Override
                public Observable<HttpObject> call(final Throwable e) {
                    if (!isInboundCanceled(e) && canRetry.get()) {
                        assignResult(ctx, RESULT.RELAY_RETRY);
                        return buildHttpResponse(dispatcher, transport, request, fullRequest, info, canRetry);
                    } else {
                        assignResult(ctx, RESULT.RELAY_FAILURE);
                        return Observable.error(e);
                    }
                }})
            .doOnNext(new Action1<HttpObject>() {
                @Override
                public void call(final HttpObject httpObj) {
                    canRetry.set(false);
                }});
    }
    
    private static boolean isInboundCanceled(final Throwable e) {
        return e instanceof TransportException;
    }

    private static void assignResult(final ResponseCtx ctx, final RESULT result) {
        if (null!=ctx.resultSetter) {
            ctx.resultSetter.call(result);
        }
    }

    class RequestSubscriber extends Subscriber<HttpObject> {
        private final HttpTrade _trade;
        private final Observable<HttpObject> _request;
      
        RequestSubscriber(final HttpTrade trade, final Observable<HttpObject> request) {
            this._trade = trade;
            this._request = request;
        }
        
        @Override
        public void onCompleted() {
        }

        @Override
        public void onError(final Throwable e) {
            LOG.warn("trade({}).request().onError ({}).", 
                this._trade, ExceptionUtils.exception2detail(e));
        }
        
        @Override
        public void onNext(final HttpObject msg) {
            if (msg instanceof HttpRequest) {
                final HttpRequest req = (HttpRequest)msg;
                final RouterCtxImpl routectx = new RouterCtxImpl();
                
                final Dispatcher dispatcher = _router.calculateRoute(req, routectx);
                final RoutingInfo info = routectx.getProperty("routingInfo");
                routectx.clear();
                
                _trade.outbound(
                    buildHttpResponse(dispatcher, _trade.transport(), req, this._request, info, new AtomicBoolean(true)));
            }
        }
    };
    
    private final Router<HttpRequest, Dispatcher> _router;
}
