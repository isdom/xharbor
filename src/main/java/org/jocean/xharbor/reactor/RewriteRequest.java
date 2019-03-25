package org.jocean.xharbor.reactor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jocean.http.FullMessage;
import org.jocean.http.MessageBody;
import org.jocean.idiom.Regexs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import com.google.common.collect.ImmutableMap;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import rx.Observable;
import rx.Single;

public class RewriteRequest extends SingleReactor {
    private static final Logger LOG = LoggerFactory.getLogger(RewriteRequest.class);

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("RewriteRequest [pathPattern=").append(_pathPattern).append(", replacePathTo=")
                .append(_replacePathTo).append(", replaceHeaderName=").append(_replaceHeaderName)
                .append(", replaceHeaderValue=").append(_replaceHeaderValue).append("]");
        return builder.toString();
    }

    @Override
    public Single<Boolean> match(final ReactContext ctx, final InOut io) {
        if (null != io.outbound()) {
            return Single.just(false);
        }
        return io.inbound().first().map(fullreq -> this._pathPattern.matcher(fullreq.message().uri()).find()).toSingle();
    }

    @Override
    public Single<? extends InOut> react(final ReactContext ctx, final InOut io) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("try {} for trade {}", this, ctx.trade());
        }
        if (null != io.outbound()) {
            return Single.<InOut>just(null);
        }
        return io.inbound().first().map(fullreq -> {
            final Matcher matcher = this._pathPattern.matcher(fullreq.message().uri());
            if (matcher.find()) {
                return io4rewritePath(ctx, io, fullreq, matcher);
            } else {
                // not handle this trade
                return null;
            }
        }).toSingle();
    }

    private InOut io4rewritePath(final ReactContext ctx,
            final InOut orgio,
            final FullMessage<HttpRequest> orgfullreq,
            final Matcher matcher) {
        final HttpRequest newreq = org2new(ctx, matcher, orgfullreq.message());
        return new InOut() {
            @Override
            public Observable<FullMessage<HttpRequest>> inbound() {
                return Observable.just(new FullMessage<HttpRequest>() {
                    @Override
                    public HttpRequest message() {
                        return newreq;
                    }
                    @Override
                    public Observable<? extends MessageBody> body() {
                        return orgfullreq.body();
                    }});
            }
            @Override
            public Observable<FullMessage<HttpResponse>> outbound() {
                return orgio.outbound();
            }};
    }

    private HttpRequest org2new(final ReactContext ctx, final Matcher matcher, final HttpRequest org) {
        final HttpRequest newreq = new DefaultHttpRequest(org.protocolVersion(), org.method(), org.uri(), true);
        newreq.headers().set(org.headers());

        if (null != this._replacePathTo && !this._replacePathTo.isEmpty()) {
            // when _replacePathTo not empty, then modify original path
            newreq.setUri(matcher.replaceFirst(this._replacePathTo));
            ctx.span().log(ImmutableMap.<String, Object>builder()
                    .put("event", "rwreq")
                    .put("uri.old", org.uri())
                    .put("uri.new", newreq.uri())
                    .build());
        }
        if (null != this._replaceHeaderName && !this._replaceHeaderName.isEmpty()) {
            newreq.headers().set(this._replaceHeaderName, _replaceHeaderValue);
            ctx.span().log(ImmutableMap.<String, Object>builder()
                    .put("event", "rwreq")
                    .put("header.name", this._replaceHeaderName)
                    .put("header.value", this._replaceHeaderValue)
                    .build());
        }
        return newreq;
    }

    @Value("${request.path}")
    void setPath(final String pattern) {
        this._pathPattern = Regexs.safeCompilePattern(pattern);
    }

    Pattern _pathPattern;

    @Value("${rewrite.header.name}")
    String _replaceHeaderName;

    @Value("${rewrite.header.value}")
    String _replaceHeaderValue;

    @Value("${rewrite.to}")
    String _replacePathTo;
}
