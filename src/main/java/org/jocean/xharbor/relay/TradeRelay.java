/**
 * 
 */
package org.jocean.xharbor.relay;

import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;

import org.jocean.http.TransportException;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.StopWatch;
import org.jocean.idiom.rx.RxObservables;
import org.jocean.idiom.rx.RxObservables.RetryPolicy;
import org.jocean.xharbor.api.TradeReactor;
import org.jocean.xharbor.api.TradeReactor.InOut;
import org.jocean.xharbor.api.TradeReactor.ReactContext;
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
        
        this._tradeReactor.react(ctx, new InOut() {
            @Override
            public Observable<? extends HttpObject> inbound() {
                return trade.inboundRequest();
            }
            @Override
            public Observable<? extends HttpObject> outbound() {
                return null;
            }})
        .retryWhen(retryPolicy(trade))
        .subscribe(new Action1<InOut>() {
            @Override
            public void call(final InOut io) {
                if (null == io.outbound()) {
                    LOG.warn("TradeRelay can't relay trade({}), NO Target.", trade);
                }
                trade.outboundResponse(buildResponse(trade.inboundRequest(), io)
                );
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
                    public HttpVersion call(final HttpRequest req) {
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

    private Func1<Observable<? extends Throwable>, ? extends Observable<?>> retryPolicy(final HttpTrade trade) {
        final RetryPolicy<Object> policy = new RetryPolicy<Object>() {
            @Override
            public Observable<Object> call(final Observable<Throwable> errors) {
                return errors.compose(RxObservables.retryIfMatch(_RETRY_IF, 100))
                        .compose(RxObservables.retryMaxTimes(_maxRetryTimes))
                        .compose(RxObservables.retryDelayTo(_retryIntervalBase))
                        ;
            }};
        return new Func1<Observable<? extends Throwable>, Observable<?>>() {
            @SuppressWarnings("unchecked")
            @Override
            public Observable<?> call(final Observable<? extends Throwable> errors) {
                return policy.call((Observable<Throwable>)errors);
            }
        };
    }
    
    private static final Func1<Throwable, Boolean> _RETRY_IF = new Func1<Throwable, Boolean>() {
        @Override
        public Boolean call(final Throwable e) {
            final boolean matched = (e instanceof TransportException)
                || (e instanceof ConnectException)
                || (e instanceof ClosedChannelException);
            if (matched) {
                LOG.info("react with error {}, and retry", ExceptionUtils.exception2detail(e));
            } else {
                LOG.warn("react with error {}, NOT in retry exception set response with internal error.", 
                    ExceptionUtils.exception2detail(e));
            }
            return matched;
        }
        @Override
        public String toString() {
            return "TransportException or ConnectException or ClosedChannelException";
        }};

    private final TradeReactor _tradeReactor;
    private int _maxRetryTimes = 3;
    private int _retryIntervalBase = 2;
}
