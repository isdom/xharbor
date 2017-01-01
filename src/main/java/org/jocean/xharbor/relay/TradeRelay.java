/**
 * 
 */
package org.jocean.xharbor.relay;

import java.util.concurrent.atomic.AtomicBoolean;

import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.HttpMessageHolder;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.StopWatch;
import org.jocean.idiom.rx.RxActions;
import org.jocean.xharbor.api.TradeReactor;
import org.jocean.xharbor.api.TradeReactor.InOut;
import org.jocean.xharbor.api.TradeReactor.ReactContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Action1;
import rx.functions.Func1;

/**
 * @author isdom
 *
 */
public class TradeRelay extends Subscriber<HttpTrade> {

    private static final Logger LOG =
            LoggerFactory.getLogger(TradeRelay.class);

    public TradeRelay(final TradeReactor reactor) {
        this._tradeReactor = reactor;
    }
    
    @Override
    public void onCompleted() {
        LOG.warn("TradeRelay {} onCompleted", this);
    }

    @Override
    public void onError(final Throwable e) {
        LOG.warn("TradeRelay {} onError, detail:{}", 
                this, ExceptionUtils.exception2detail(e));
    }

    @Override
    public void onNext(final HttpTrade trade) {
        final HttpMessageHolder holder = new HttpMessageHolder(0);
        final Observable<? extends HttpObject> cachedInbound = 
            trade.doOnClosed(RxActions.<HttpTrade>toAction1(holder.release()))
            .inboundRequest()
            .compose(holder.assembleAndHold())
            .cache()
            .compose(RxNettys.duplicateHttpContent())
            ;

        final StopWatch watch4Result = new StopWatch();
        final ReactContext ctx = new ReactContext() {
            @Override
            public HttpTrade trade() {
                return trade;
            }
            @Override
            public StopWatch watch() {
                return watch4Result;
            }};
        
        final AtomicBoolean isKeepAliveFromClient = new AtomicBoolean(true);
        
        this._tradeReactor.react(ctx, new InOut() {
            @Override
            public Observable<? extends HttpObject> inbound() {
                return cachedInbound.map(new Func1<HttpObject, HttpObject>() {
                    @Override
                    public HttpObject call(final HttpObject httpobj) {
                        if (httpobj instanceof HttpRequest) {
                            final HttpRequest req = (HttpRequest)httpobj;
                            //  only check first time, bcs inbound could be process many times
                            if (!req.method().equals(HttpMethod.HEAD) 
                                    && isKeepAliveFromClient.get()) {
                                isKeepAliveFromClient.set(HttpUtil.isKeepAlive(req));
                                if (!isKeepAliveFromClient.get()) {
                                    // if NOT keep alive, force it
                                    //  TODO, need to duplicate req?
                                    req.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                                    LOG.info("FORCE-KeepAlive: add Connection header with KeepAlive for incoming req:\n[{}]", req);
                                }
                            }
                        }
                        return httpobj;
                    }});
            }
            @Override
            public Observable<? extends HttpObject> outbound() {
                return null;
            }})
        .subscribe(new Action1<InOut>() {
            @Override
            public void call(final InOut io) {
                if (null == io.outbound()) {
                    LOG.warn("TradeRelay can't relay trade({}), NO Target.", trade);
                }
                trade.outboundResponse(buildResponse(cachedInbound, io).map(
                new Func1<HttpObject, HttpObject>() {
                    @Override
                    public HttpObject call(final HttpObject httpobj) {
                        if (httpobj instanceof HttpResponse) {
                            final HttpResponse resp = (HttpResponse)httpobj;
                            if (!isKeepAliveFromClient.get()) {
                                // if NOT keep alive from client, remove keepalive header
                                //  TODO, need to duplicate resp?
                                resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                                LOG.info("FORCE-KeepAlive: set Connection header with Close for sendback resp:\n[{}]", resp);
                            }
                        }
                        return httpobj;
                    }}));
            }}, new Action1<Throwable>() {
            @Override
            public void call(final Throwable error) {
                LOG.warn("Trade {} react with error, detail:{}", 
                        trade, ExceptionUtils.exception2detail(error));
                trade.abort();
            }});
    }
    
    private Observable<? extends HttpObject> buildResponse(
            final Observable<? extends HttpObject> originalInbound, final InOut io) {
        return null != io.outbound()
            ? io.outbound()
            : originalInbound.compose(RxNettys.asHttpRequest())
                .map(new Func1<HttpRequest, HttpVersion>() {
                    @Override
                    public HttpVersion call(
                            final HttpRequest req) {
                        return null != req ? req.protocolVersion() : HttpVersion.HTTP_1_1;
                    }})
                .flatMap(new Func1<HttpVersion, Observable<HttpObject>>() {
                    @Override
                    public Observable<HttpObject> call(
                            final HttpVersion version) {
                        return RxNettys.response200OK(version);
                    }})
                .delaySubscription(originalInbound.ignoreElements());// response when request send completed
    }

    private final TradeReactor _tradeReactor;
}
