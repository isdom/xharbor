package org.jocean.xharbor.reactor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jocean.http.HttpSlice;
import org.jocean.http.HttpSliceUtil;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.Regexs;
import org.jocean.xharbor.api.TradeReactor;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import rx.Observable;
import rx.Single;

public class RewriteRequest implements TradeReactor {

    public RewriteRequest(
            final String pathPattern,
            final String replacePathTo,
            final String replaceHeaderName,
            final String replaceHeaderValue
            ) {
        this._pathPattern = Regexs.safeCompilePattern(pathPattern);
        this._replacePathTo = replacePathTo;
        this._replaceHeaderName = replaceHeaderName;
        this._replaceHeaderValue = replaceHeaderValue;
    }

    @Override
    public Single<? extends InOut> react(final ReactContext ctx, final InOut io) {
        if (null != io.outbound()) {
            return Single.<InOut>just(null);
        }
        return io.inbound().first().compose(HttpSliceUtil.<HttpRequest>extractHttpMessage()).map(req -> {
            if (null == req) {
                return null;
            } else {
                final Matcher matcher = this._pathPattern.matcher(req.uri());
                if (matcher.find()) {
                    return io4rewritePath(io, req, matcher);
                } else {
                    // not handle this trade
                    return null;
                }
            }
        }).toSingle();
    }

    private InOut io4rewritePath(final InOut orgio, final HttpRequest orgreq, final Matcher matcher) {
        return new InOut() {
            @Override
            public Observable<? extends HttpSlice> inbound() {
                return orgio.inbound().first().map(HttpSliceUtil.transformElement(element ->
                    Observable.<DisposableWrapper<? extends HttpObject>>just(RxNettys.wrap4release(org2new(matcher, orgreq)))
                    .concatWith(element.flatMap(RxNettys.splitdwhs()).skip(1))
                )).concatWith(orgio.inbound().skip(1));
            }
            @Override
            public Observable<? extends HttpSlice> outbound() {
                return orgio.outbound();
            }};
    }

    private HttpRequest org2new(final Matcher matcher, final HttpRequest org) {
        final DefaultHttpRequest newreq = new DefaultHttpRequest(org.protocolVersion(), org.method(), org.uri(), true);
        newreq.headers().set(org.headers());
        if (null != this._replacePathTo && !this._replacePathTo.isEmpty()) {
            // when _replacePathTo not empty, then modify original path
            newreq.setUri(matcher.replaceFirst(this._replacePathTo));
        }
        if (null != this._replaceHeaderName && !this._replaceHeaderName.isEmpty()) {
            newreq.headers().set(this._replaceHeaderName, _replaceHeaderValue);
        }
        return newreq;
    }

    private final Pattern _pathPattern;
    private final String _replacePathTo;
    private final String _replaceHeaderName;
    private final String _replaceHeaderValue;
}
