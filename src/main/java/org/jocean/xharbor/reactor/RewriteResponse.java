package org.jocean.xharbor.reactor;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

import org.jocean.http.FullMessage;
import org.jocean.xharbor.api.TradeReactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import rx.Observable;
import rx.Single;

public class RewriteResponse implements TradeReactor {
    private static final Logger LOG = LoggerFactory.getLogger(RewriteResponse.class);

    public RewriteResponse(final MatchRule matcher, final Map<String, String> extraHeaders) {
        this._matcher = matcher;
        this._extraHeaders = extraHeaders;
    }

    @Override
    public String toString() {
        final int maxLen = 10;
        final StringBuilder builder = new StringBuilder();
        builder.append("RewriteResponse [matcher=").append(_matcher).append(", extraHeaders=")
                .append(_extraHeaders != null ? toString(_extraHeaders.entrySet(), maxLen) : null).append("]");
        return builder.toString();
    }

    private String toString(final Collection<?> collection, final int maxLen) {
        final StringBuilder builder = new StringBuilder();
        builder.append("[");
        int i = 0;
        for (final Iterator<?> iterator = collection.iterator(); iterator.hasNext() && i < maxLen; i++) {
            if (i > 0)
                builder.append(", ");
            builder.append(iterator.next());
        }
        builder.append("]");
        return builder.toString();
    }

    @Override
    public Single<? extends InOut> react(final ReactContext ctx, final InOut io) {
        LOG.trace("try {} for trade {}", this, ctx.trade());
        if (null == io.outbound()) {
            return Single.<InOut>just(null);
        }
        return io.inbound().first().map(fullreq -> {
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
