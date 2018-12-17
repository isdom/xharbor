/**
 *
 */
package org.jocean.xharbor.relay;

import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;

import org.jocean.http.FullMessage;
import org.jocean.http.MessageBody;
import org.jocean.http.TransportException;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.StepableUtil;
import org.jocean.idiom.StopWatch;
import org.jocean.idiom.rx.RxObservables;
import org.jocean.idiom.rx.RxObservables.RetryPolicy;
import org.jocean.xharbor.api.TradeReactor;
import org.jocean.xharbor.api.TradeReactor.InOut;
import org.jocean.xharbor.api.TradeReactor.ReactContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import rx.Observable;
import rx.Observable.Transformer;
import rx.Subscriber;
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
        LOG.warn("TradeRelay {} onError, detail:{}", this, ExceptionUtils.exception2detail(e));
    }

    @Override
    public void onNext(final HttpTrade trade) {
        LOG.debug("TradeRelay {} onNext, trade {}", this, trade);
        final StopWatch watch4Result = new StopWatch();
        final ReactContext ctx = new ReactContext() {
            @Override
            public HttpTrade trade() {
                return trade;
            }

            @Override
            public StopWatch watch() {
                return watch4Result;
            }
        };

        this._tradeReactor.react(ctx, new InOut() {
            @Override
            public Observable<FullMessage<HttpRequest>> inbound() {
                return trade.inbound();
            }

            @Override
            public Observable<FullMessage<HttpResponse>> outbound() {
                return null;
            }
        }).retryWhen(retryPolicy(trade)).subscribe(io -> {
                if (null == io || null == io.outbound()) {
                    LOG.warn("NO_INOUT for trade({}), react io detail: {}.", trade, io);
                }
                trade.outbound(buildResponse(trade, io).compose(fullresp2objs()));
            }, error -> {
                LOG.warn("Trade {} react with error, detail:{}", trade, ExceptionUtils.exception2detail(error));
                trade.close();
            });
    }

    private Transformer<FullMessage<HttpResponse>, Object> fullresp2objs() {
        return getfullresp -> getfullresp.flatMap(fullresp ->
            Observable.<Object>just(fullresp.message()).concatWith(fullresp.body().concatMap(body -> body.content()))
                .concatWith(Observable.just(LastHttpContent.EMPTY_LAST_CONTENT)));
    }

    private Observable<FullMessage<HttpResponse>> buildResponse(final HttpTrade trade, final InOut io) {
        return (null != io && null != io.outbound()) ? io.outbound() : defaultResponse200(trade);
    }

    private Observable<FullMessage<HttpResponse>> defaultResponse200(final HttpTrade trade) {
        return trade.inbound().map(fullreq -> (FullMessage<HttpResponse>)new FullMessage<HttpResponse>() {
            @Override
            public HttpResponse message() {
                final HttpResponse response = new DefaultHttpResponse(
                        fullreq.message().protocolVersion(), HttpResponseStatus.OK);
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
                return response;
            }
            @Override
            public Observable<? extends MessageBody> body() {
                return Observable.empty();
            }} )
            // response when request send completed
            .delaySubscription(trade.inbound().flatMap(fullmsg -> fullmsg.body()).flatMap(body -> body.content())
                .compose(StepableUtil.autostep2element2()).doOnNext(bbs -> bbs.dispose()).ignoreElements());
    }

    @SuppressWarnings("unchecked")
    private Func1<Observable<? extends Throwable>, ? extends Observable<?>> retryPolicy(final HttpTrade trade) {
        final RetryPolicy<Integer> policy = new RetryPolicy<Integer>() {
            @Override
            public Observable<Integer> call(final Observable<Throwable> errors) {
                return (Observable<Integer>) errors.compose(RxObservables.retryIfMatch(ifMatch(trade), 100))
                        .compose(RxObservables.retryMaxTimes(_maxRetryTimes))
                        .compose(RxObservables.retryDelayTo(_retryIntervalBase))
                        .doOnNext( retryCount ->
                            LOG.info("FORWARD_RETRY with retry-count {} for trade {}", retryCount, trade))
                        ;
            }};
        return errors -> policy.call((Observable<Throwable>)errors);
    }

    private static Func1<Throwable, Boolean> ifMatch(final HttpTrade trade) {
        return new Func1<Throwable, Boolean>() {
            @Override
            public Boolean call(final Throwable e) {
                //  TODO, check obsrequest has been disposed ?
//                if (trade.inboundHolder().isFragmented()) {
//                    LOG.warn("NOT_RETRY for trade({}), bcs of trade's inbound has fragmented.",
//                            trade);
//                    return false;
//                }
                final boolean matched = (e instanceof TransportException)
                    || (e instanceof ConnectException)
                    || (e instanceof ClosedChannelException);
                if (matched) {
                    LOG.info("RETRY for trade({}), bcs of error: {}",
                            trade, ExceptionUtils.exception2detail(e));
                } else {
                    LOG.warn("NOT_RETRY for trade({}), bcs of error: {}",
                            trade, ExceptionUtils.exception2detail(e));
                }
                return matched;
            }
            @Override
            public String toString() {
                return "TransportException or ConnectException or ClosedChannelException";
            }
        };
    }

    private final TradeReactor _tradeReactor;
    private final int _maxRetryTimes = 3;
    private final int _retryIntervalBase = 2;
}
