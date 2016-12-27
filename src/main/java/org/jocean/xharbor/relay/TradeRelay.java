/**
 * 
 */
package org.jocean.xharbor.relay;

import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.HttpMessageHolder;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.StopWatch;
import org.jocean.idiom.rx.RxActions;
import org.jocean.xharbor.api.TradeReactor;
import org.jocean.xharbor.api.TradeReactor.InOut;
import org.jocean.xharbor.api.TradeReactor.TradeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
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
        final TradeContext ctx = new TradeContext() {
            @Override
            public HttpTrade trade() {
                return trade;
            }
            @Override
            public StopWatch watch() {
                return watch4Result;
            }};
        
        this._tradeReactor.react(ctx, new InOut() {
            @Override
            public Observable<? extends HttpObject> inbound() {
                return cachedInbound;
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
                trade.outboundResponse(buildResponse(cachedInbound, io));
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
