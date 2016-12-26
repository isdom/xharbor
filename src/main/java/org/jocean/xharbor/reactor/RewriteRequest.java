package org.jocean.xharbor.reactor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.Regexs;
import org.jocean.xharbor.api.TradeReactor;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import rx.Observable;
import rx.Single;
import rx.functions.Func1;

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
    public Single<? extends InOut> react(final HttpTrade trade, final InOut io) {
        if (null != io.outbound()) {
            return Single.<InOut>just(null);
        }
        return io.inbound().compose(RxNettys.asHttpRequest())
            .map(new Func1<HttpRequest, InOut>() {
                @Override
                public InOut call(final HttpRequest req) {
                    if (null == req) {
                        return null;
                    } else {
                        final Matcher matcher = _pathPattern.matcher(req.uri());
                        if ( matcher.find() ) {
                            return io4rewritePath(io, req, matcher);
                        } else {
                            //  not handle this trade
                            return null;
                        }
                    }
                }})
            .toSingle();
    }

    private HttpRequest rewriteRequest(
            final Matcher matcher,
            final HttpRequest req) {
        final DefaultHttpRequest newreq = new DefaultHttpRequest(req.protocolVersion(), 
                req.method(), req.uri(), true);
        newreq.headers().set(req.headers());
        newreq.setUri(matcher.replaceFirst(_replacePathTo));
        if (null != this._replaceHeaderName
            && !this._replaceHeaderName.isEmpty()) {
            newreq.headers().set(this._replaceHeaderName, _replaceHeaderValue);
        }
        return newreq;
    }

    private InOut io4rewritePath(final InOut originalio, 
            final HttpRequest originalreq,
            final Matcher matcher) {
        return new InOut() {
            @Override
            public Observable<? extends HttpObject> inbound() {
                return Observable.<HttpObject>just(rewriteRequest(matcher, originalreq))
                    .concatWith(
                        originalio.inbound()
                        .flatMap(RxNettys.splitFullHttpMessage())
                        .skip(1));
            }
            @Override
            public Observable<? extends HttpObject> outbound() {
                return originalio.outbound();
            }};
    }

    private final Pattern _pathPattern;
    private final String _replacePathTo;
    private final String _replaceHeaderName;
    private final String _replaceHeaderValue;
}
