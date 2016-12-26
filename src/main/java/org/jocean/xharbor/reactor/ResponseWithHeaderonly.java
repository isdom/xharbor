package org.jocean.xharbor.reactor;

import java.util.Map;

import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.RxNettys;
import org.jocean.xharbor.api.TradeReactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import rx.Observable;
import rx.Single;
import rx.functions.Action0;
import rx.functions.Func1;

public class ResponseWithHeaderonly implements TradeReactor {
    
    private static final Logger LOG = LoggerFactory
            .getLogger(ResponseWithHeaderonly.class);

    public ResponseWithHeaderonly(
            final MatchRule matcher,
            final int responseStatus, 
            final Map<String, String> extraHeaders,
            final boolean enableLogReact) {
        this._matcher = matcher;
        this._responseStatus = responseStatus;
        this._extraHeaders = extraHeaders;
        this._logReact = enableLogReact;
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
                            LOG.warn("request is null, ignore trade {}", trade);
                            return null;
                        } else {
                            if (_matcher.match(req)) {
                                return io4Response(io, req);
                            } else {
                                //  not handle this trade
                                return null;
                            }
                        }
                    }})
                .toSingle();
    }

    private InOut io4Response(final InOut originalio, 
            final HttpRequest originalreq) {
        return new InOut() {
            @Override
            public Observable<? extends HttpObject> inbound() {
                return originalio.inbound();
            }
            @Override
            public Observable<? extends HttpObject> outbound() {
                final FullHttpResponse response = new DefaultFullHttpResponse(
                        originalreq.protocolVersion(), HttpResponseStatus.valueOf(_responseStatus));
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
                if (null!=_extraHeaders) {
                    for (Map.Entry<String, String> entry : _extraHeaders.entrySet()) {
                        response.headers().set(entry.getKey(), entry.getValue());
                    }
                }
                return Observable.<HttpObject>just(response)
                    .delaySubscription(originalio.inbound().ignoreElements())
                    .doOnCompleted(new Action0() {
                        @Override
                        public void call() {
                            if (_logReact) {
                                LOG.info("RESPOND sendback response directly:\nREQ\n[{}]\nRESP\n[{}]", 
                                    originalreq, response);
                            }
                        }})
                    ;
            }};
    }
    
    private final MatchRule _matcher;
    private final int _responseStatus;
    private final Map<String, String> _extraHeaders;
    private final boolean _logReact;
}
