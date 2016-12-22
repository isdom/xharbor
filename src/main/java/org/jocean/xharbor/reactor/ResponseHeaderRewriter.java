package org.jocean.xharbor.reactor;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.RxNettys;
import org.jocean.xharbor.api.TradeReactor;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import rx.Observable;
import rx.Single;
import rx.functions.Action1;
import rx.functions.Func1;

public class ResponseHeaderRewriter implements TradeReactor {
    public ResponseHeaderRewriter(
            final String pathPattern,
            final Map<String, String> extraHeaders) {
        this._pathPattern = safeCompilePattern(pathPattern);
        this._extraHeaders = extraHeaders;
    }
    
    private static Pattern safeCompilePattern(final String regex) {
        return null != regex && !"".equals(regex) ? Pattern.compile(regex) : null;
    }
    
    @Override
    public Single<? extends InOut> react(final HttpTrade trade, final InOut io) {
        if (null == io.outbound()) {
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
                                return io4rewriteResponse(io, req);
                            } else {
                                //  not handle this trade
                                return null;
                            }
                        }
                    }})
                .toSingle();
    }
    
    private InOut io4rewriteResponse(final InOut originalio, 
            final HttpRequest originalreq) {
        return new InOut() {
            @Override
            public Observable<? extends HttpObject> inbound() {
                return originalio.inbound();
            }
            @Override
            public Observable<? extends HttpObject> outbound() {
                return originalio.outbound().doOnNext(new Action1<HttpObject>() {
                    @Override
                    public void call(final HttpObject httpobj) {
                        if (httpobj instanceof HttpResponse) {
                            final HttpResponse response = (HttpResponse)httpobj;
                            for (Map.Entry<String, String> entry : _extraHeaders.entrySet()) {
                                response.headers().add(entry.getKey(), entry.getValue());
                            }
                        }
                    }});
            }};
    }
    
    private final Pattern _pathPattern;
    private final Map<String, String> _extraHeaders;
}
