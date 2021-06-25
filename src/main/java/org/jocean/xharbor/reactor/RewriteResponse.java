package org.jocean.xharbor.reactor;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import org.jocean.http.FullMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import rx.Observable;
import rx.Single;

public class RewriteResponse extends SingleReactor {
    private static final Logger LOG = LoggerFactory.getLogger(RewriteResponse.class);


    @Override
    public String toString() {
        final int maxLen = 10;
        final StringBuilder builder = new StringBuilder();
        builder.append("RewriteResponse [_matcher=").append(_matcher).append(", _extraHeaders=")
                .append(_extraHeaders != null ? toString(_extraHeaders.entrySet(), maxLen) : null)
                .append(", _removeHeaderName=").append(_removeHeaderName).append("]");
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
    public Single<Boolean> match(final ReactContext ctx, final InOut io) {
        if (null == io.outbound()) {
            return Single.just(false);
        }
        return io.inbound().first().map(fullreq -> this._matcher.match(fullreq.message())).toSingle();
    }

    @Override
    public Single<? extends InOut> react(final ReactContext ctx, final InOut io) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("try {} for trade {}", this, ctx.trade());
        }
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
                        if (null != _extraHeaders) {
                            for (final Map.Entry<String, String> entry : _extraHeaders.entrySet()) {
                                response.headers().set(entry.getKey(), entry.getValue());
                            }
                        }
                        if (null != _removeHeaderName) {
                            response.headers().remove(_removeHeaderName);
                        }
                });
            }
        };
    }

    @Inject
    MatchRule _matcher;

    @Inject
    @Named("extraHeaders")
    Map<String, String> _extraHeaders;

    @Value("${remove.header.name}")
    String _removeHeaderName;
}
