/**
 * 
 */
package org.jocean.xharbor.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jocean.http.Feature;
import org.jocean.http.client.HttpClient;
import org.jocean.http.server.HttpServer.HttpTrade;
import org.jocean.idiom.rx.OneshotSubscription;
import org.jocean.xharbor.api.Dispatcher;
import org.jocean.xharbor.api.RelayMemo;
import org.jocean.xharbor.api.Router;
import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.api.RoutingInfoMemo;
import org.jocean.xharbor.api.Target;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Func1;
import rx.observers.SerializedSubscriber;

/**
 * @author isdom
 *
 */
public class RelayTrade extends Subscriber<HttpTrade> {

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
    
    @SuppressWarnings("unused")
    private static final Logger LOG =
            LoggerFactory.getLogger(RelayTrade.class);

    public RelayTrade(
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
            private final List<HttpObject> _reqHttpObjects = new ArrayList<>();
            private final List<Subscriber<? super HttpObject>> _subscribers = new ArrayList<>();
            private boolean _isCompleted = false;
          
            private Observable<? extends HttpObject> cachedRequest() {
                return Observable.create(new OnSubscribe<HttpObject>() {
                    @Override
                    public void call(final Subscriber<? super HttpObject> subscriber) {
                        trade.requestExecutor().execute(new Runnable() {
                            @Override
                            public void run() {
                                if (!subscriber.isUnsubscribed()) {
                                    for (HttpObject httpObj : _reqHttpObjects ) {
                                        subscriber.onNext(httpObj);
                                    }
                                    if (_isCompleted) {
                                        subscriber.onCompleted();
                                    }
                                    _subscribers.add(subscriber);
                                    subscriber.add(new OneshotSubscription() {
                                        @Override
                                        protected void doUnsubscribe() {
                                            trade.requestExecutor().execute(new Runnable() {
                                                @Override
                                                public void run() {
                                                    _subscribers.remove(subscriber);
                                                }});
                                        }});
                                }
                            }});
                    }});
            }
            
            private void destructor() {
                // release all HttpObjects of request
                for (HttpObject obj : this._reqHttpObjects) {
                    ReferenceCountUtil.release(obj);
                }
                this._reqHttpObjects.clear();
            }
            
            private FullHttpRequest retainFullHttpRequest() {
                if (this._reqHttpObjects.size()>0) {
                    if (this._reqHttpObjects.get(0) instanceof FullHttpRequest) {
                        return ((FullHttpRequest)this._reqHttpObjects.get(0)).retain();
                    }
                    
                    final HttpRequest req = (HttpRequest)this._reqHttpObjects.get(0);
                    final ByteBuf[] bufs = new ByteBuf[this._reqHttpObjects.size()-1];
                    for (int idx = 1; idx<this._reqHttpObjects.size(); idx++) {
                        bufs[idx-1] = ((HttpContent)this._reqHttpObjects.get(idx)).content().retain();
                    }
                    return new DefaultFullHttpRequest(
                            req.getProtocolVersion(), 
                            req.getMethod(), 
                            req.getUri(), 
                            Unpooled.wrappedBuffer(bufs));
                } else {
                    return null;
                }
            }
            
            @Override
            public void onCompleted() {
                this._isCompleted = true;
                for (Subscriber<? super HttpObject> subscriber : this._subscribers ) {
                    subscriber.onCompleted();
                }
                //destructor();
            }

            @Override
            public void onError(final Throwable e) {
                destructor();
            }
            
            @Override
            public void onNext(final HttpObject msg) {
                this._reqHttpObjects.add(ReferenceCountUtil.retain(msg));
                if (msg instanceof HttpRequest) {
                    this._request = (HttpRequest)msg;
                    final RouterCtxImpl routectx = new RouterCtxImpl();
                    
                    final Dispatcher dispatcher = _router.calculateRoute(this._request, routectx);
                    final RoutingInfo info = routectx.getProperty("routingInfo");
                    routectx.clear();
                    
                    final Target target = null != dispatcher ? dispatcher.dispatch() : null;
                    
                    if ( null == target ) {
//                        LOG.warn("can't found matched target service for request:[{}]\njust return 200 OK for client http connection ({}).", 
//                                _requestWrapper, _channelCtx.channel());
                        _noRoutingMemo.incRoutingInfo(info);
//                        setEndReason("relay.NOROUTING");
                        return;
//                        return  recvFullRequestAndResponse200OK();
                    }
                    
//                    if (MONITOR_CHECKALIVE.equalsIgnoreCase(_target.serviceUri().toString())) {
//                        setEndReason("relay.CHECKALIVE."+_target.serviceUri().toString().replace(':', '-'));
//                        return  recvFullRequestAndResponse200OK();
//                    }
                    final RelayMemo memo = _memoBuilder.build(target, info);
//                    final StepMemo<STEP> stepmemo = BizMemo.Util.buildStepMemo(memo, _watch4Step);
                            
//                    if (_target.isNeedAuthorization(_requestWrapper.request())) {
//                        setEndReason("relay.HTTP_UNAUTHORIZED");
//                        return recvFullRequestAndResponse401Unauthorized();
//                    }
                    
//                    _transformer = _target.getHttpRequestTransformerOf(_requestWrapper.request());
//                    
//                    _stepmemo.beginBizStep(STEP.ROUTING);
//                    
//                    if ( LOG.isDebugEnabled() ) {
//                        LOG.debug("dispatch to ({}) for request({})", serviceUri(), _requestWrapper);
//                    }
//                    
//                    _stepmemo.beginBizStep(STEP.OBTAINING_HTTPCLIENT);
//                    
                    //  add temp for enable rewrite 2015.03.26
                    this._request.setUri(
                        target.rewritePath(this._request.getUri()));
                    
                    _httpClient.defineInteraction(
                        new InetSocketAddress(
                            target.serviceUri().getHost(), 
                            target.serviceUri().getPort()), 
                        cachedRequest(),
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
                                destructor();
                            }})
                        .subscribe(trade.responseObserver());
                }
                for (Subscriber<? super HttpObject> subscriber : this._subscribers ) {
                    subscriber.onNext(msg);
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
