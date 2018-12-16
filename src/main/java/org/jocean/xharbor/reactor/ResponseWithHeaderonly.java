package org.jocean.xharbor.reactor;

import java.util.Map;

import org.jocean.http.FullMessage;
import org.jocean.http.MessageBody;
import org.jocean.idiom.StepableUtil;
import org.jocean.xharbor.api.TradeReactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import rx.Observable;
import rx.Single;

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
    public Single<? extends InOut> react(final ReactContext ctx, final InOut io) {
        if (null != io.outbound()) {
            return Single.<InOut>just(null);
        }
        return io.inbound().map(fullreq -> {
            if (this._matcher.match(fullreq.message())) {
                return io4Response(ctx, io, fullreq);
            } else {
                // not handle this trade
                return null;
            }
        }).toSingle();
    }

    private InOut io4Response(final ReactContext ctx, final InOut orgio, final FullMessage<HttpRequest> orgfullreq) {
        return new InOut() {
            @Override
            public Observable<FullMessage<HttpRequest>> inbound() {
                return orgio.inbound();
            }
            @Override
            public Observable<FullMessage<HttpResponse>> outbound() {
                final HttpResponse response = new DefaultHttpResponse(
                        orgfullreq.message().protocolVersion(), HttpResponseStatus.valueOf(_responseStatus));
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
                if (null!=_extraHeaders) {
                    for (final Map.Entry<String, String> entry : _extraHeaders.entrySet()) {
                        response.headers().set(entry.getKey(), entry.getValue());
                    }
                }
                return Observable.<FullMessage<HttpResponse>>just(new FullMessage<HttpResponse>() {
                    @Override
                    public HttpResponse message() {
                        return response;
                    }
                    @Override
                    public Observable<? extends MessageBody> body() {
                        return Observable.empty();
                    }})
                    .doOnCompleted(() -> {
                        if (_logReact) {
                            LOG.info("RESPOND sendback response directly:\nREQ\n[{}]\nRESP\n[{}]", orgfullreq.message(), response);
                        }
                    }).delaySubscription(orgio.inbound().flatMap(fullmsg -> fullmsg.body()).flatMap(body -> body.content())
                            .compose(StepableUtil.autostep2element2()).doOnNext(bbs -> bbs.dispose()).ignoreElements());
            }};
    }

    private final MatchRule _matcher;
    private final int _responseStatus;
    private final Map<String, String> _extraHeaders;
    private final boolean _logReact;
}
