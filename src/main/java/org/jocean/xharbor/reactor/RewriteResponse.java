package org.jocean.xharbor.reactor;

import java.util.Map;

import org.jocean.http.FullMessage;
import org.jocean.xharbor.api.TradeReactor;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import rx.Observable;
import rx.Single;

public class RewriteResponse implements TradeReactor {

    public RewriteResponse(final MatchRule matcher, final Map<String, String> extraHeaders) {
        this._matcher = matcher;
        this._extraHeaders = extraHeaders;
    }

    @Override
    public Single<? extends InOut> react(final ReactContext ctx, final InOut io) {
        if (null == io.outbound()) {
            return Single.<InOut>just(null);
        }
        return io.inbound().map(fullreq -> {
            if (_matcher.match(fullreq.message())) {
                return io4rewriteResponse(io, fullreq);
            } else {
                // not handle this trade
                return null;
            }
        }).toSingle();
    }

    private InOut io4rewriteResponse(final InOut orgio, final FullMessage<HttpRequest> orgreq) {
        return new InOut() {
            @Override
            public Observable<FullMessage<HttpRequest>> inbound() {
                return orgio.inbound();
            }

            @Override
            public Observable<FullMessage<HttpResponse>> outbound() {
                return orgio.outbound().doOnNext(fullresp -> {
                        final HttpResponse response = (HttpResponse) fullresp.message();
                        for (final Map.Entry<String, String> entry : _extraHeaders.entrySet()) {
                            response.headers().set(entry.getKey(), entry.getValue());
                        }
                });
            }
        };
    }

    private final MatchRule _matcher;
    private final Map<String, String> _extraHeaders;
}
