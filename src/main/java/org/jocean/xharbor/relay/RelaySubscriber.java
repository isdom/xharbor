/**
 * 
 */
package org.jocean.xharbor.relay;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

import org.jocean.http.Feature;
import org.jocean.http.client.HttpClient;
import org.jocean.http.server.CachedRequest;
import org.jocean.http.server.HttpServer.HttpTrade;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.StopWatch;
import org.jocean.idiom.stats.BizMemo;
import org.jocean.idiom.stats.BizMemo.StepMemo;
import org.jocean.xharbor.api.Dispatcher;
import org.jocean.xharbor.api.RelayMemo;
import org.jocean.xharbor.api.RelayMemo.STEP;
import org.jocean.xharbor.api.Router;
import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.api.RoutingInfoMemo;
import org.jocean.xharbor.api.Target;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Func1;
import rx.observers.SerializedSubscriber;

/**
 * @author isdom
 *
 */
public class RelaySubscriber extends Subscriber<HttpTrade> {

    private static final String MONITOR_CHECKALIVE = "monitor://checkalive";
    
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

    public RelaySubscriber(
            final Router<HttpRequest, Dispatcher> router, 
            final RelayMemo.Builder memoBuilder,
            final RoutingInfoMemo   noRoutingMemo,
            final HttpClient   httpClient
            ) {
        this._router = router;
        this._memoBuilder = memoBuilder;
        this._noRoutingMemo = noRoutingMemo;
        this._httpClient = httpClient;
    }
    
    @Override
    public void onCompleted() {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void onError(final Throwable e) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void onNext(final HttpTrade trade) {
        final Subscriber<HttpObject> subscriber = 
                new Subscriber<HttpObject>() {
            private HttpRequest _request;
            private CachedRequest _cached = new CachedRequest(trade);
            private final StopWatch _watch4Step = new StopWatch();
            private final StopWatch _watch4Result = new StopWatch();
          
            @Override
            public void onCompleted() {
            }

            @Override
            public void onError(final Throwable e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("trade({}).request().onError ({}).", 
                        trade, ExceptionUtils.exception2detail(e));
                }
                _cached.destroy();
            }
            
            @Override
            public void onNext(final HttpObject msg) {
                if (msg instanceof HttpRequest) {
                    this._request = (HttpRequest)msg;
                    final RouterCtxImpl routectx = new RouterCtxImpl();
                    
                    final Dispatcher dispatcher = _router.calculateRoute(this._request, routectx);
                    final RoutingInfo info = routectx.getProperty("routingInfo");
                    routectx.clear();
                    
                    final Target target = null != dispatcher ? dispatcher.dispatch() : null;
                    
                    if ( null == target ) {
                        LOG.warn("can't found matched target service for request:[{}]\njust return 200 OK for trade ({}).", 
                                msg, trade);
                        _noRoutingMemo.incRoutingInfo(info);
//                        setEndReason("relay.NOROUTING");
                        final HttpVersion version = _request.getProtocolVersion();
                        _cached.request()
                            .doOnCompleted(new Action0() {
                                @Override
                                public void call() {
                                    RxNettys.response200OK(version)
                                        .subscribe(trade.responseObserver());
                                }})
                            .subscribe();
                        return;
                    }
                    
                    if (MONITOR_CHECKALIVE.equalsIgnoreCase(target.serviceUri().toString())) {
//                        setEndReason("relay.CHECKALIVE."+_target.serviceUri().toString().replace(':', '-'));
                        final HttpVersion version = _request.getProtocolVersion();
                        _cached.request()
                            .doOnCompleted(new Action0() {
                                @Override
                                public void call() {
                                    RxNettys.response200OK(version)
                                        .subscribe(trade.responseObserver());
                                }})
                            .subscribe();
                        return;
                    }
                    final RelayMemo memo = _memoBuilder.build(target, info);
                    final StepMemo<STEP> stepmemo = BizMemo.Util.buildStepMemo(memo, this._watch4Step);

                    if (target.isNeedAuthorization(this._request)) {
//                        setEndReason("relay.HTTP_UNAUTHORIZED");
                        final HttpVersion version = _request.getProtocolVersion();
                        _cached.request()
                            .doOnCompleted(new Action0() {
                                @Override
                                public void call() {
                                    RxNettys.response401Unauthorized(
                                            version,
                                            "Basic realm=\"iplusmed\"")
                                        .subscribe(trade.responseObserver());
                                }})
                            .subscribe();
                        return;
                    }
                    
//                    _transformer = _target.getHttpRequestTransformerOf(_requestWrapper.request());
//                    
                    stepmemo.beginBizStep(STEP.ROUTING);
//                    
                    if ( LOG.isDebugEnabled() ) {
                        LOG.debug("dispatch to ({}) for request({})", target.serviceUri(), msg);
                    }
                    
                    stepmemo.beginBizStep(STEP.OBTAINING_HTTPCLIENT);
//                    
                    //  add temp for enable rewrite 2015.03.26
                    this._request.setUri(
                        target.rewritePath(this._request.getUri()));
                    
                    _httpClient.defineInteraction(
                        new InetSocketAddress(
                            target.serviceUri().getHost(), 
                            target.serviceUri().getPort()), 
                            _cached.request(),
                        Feature.ENABLE_LOGGING)
                        .filter(new Func1<Object, Boolean>() {
                            @Override
                            public Boolean call(Object in) {
                                return in instanceof HttpObject;
                            }})
                         .map(new Func1<Object, HttpObject>() {
                            @Override
                            public HttpObject call(Object in) {
                                return (HttpObject)in;
                            }})
                        .doOnTerminate(new Action0() {
                            @Override
                            public void call() {
                                _cached.destroy();
                            }})
                        .subscribe(trade.responseObserver());
                }
            }
        };
        
        trade.request().subscribe(
            new SerializedSubscriber<HttpObject>(subscriber));
    }
    
    private final HttpClient _httpClient;
    private final RelayMemo.Builder _memoBuilder;
    private final RoutingInfoMemo _noRoutingMemo;
    private final Router<HttpRequest, Dispatcher> _router;
}
