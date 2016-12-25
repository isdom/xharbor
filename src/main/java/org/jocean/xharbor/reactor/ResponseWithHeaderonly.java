package org.jocean.xharbor.reactor;

import java.util.Map;
import java.util.regex.Pattern;

import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.Regexs;
import org.jocean.xharbor.api.TradeReactor;

import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import rx.Observable;
import rx.Single;
import rx.functions.Func1;

public class ResponseWithHeaderonly implements TradeReactor {

    public ResponseWithHeaderonly(
            final String methodPattern, 
            final String pathPattern, 
            final int responseStatus, 
            final Map<String, String> extraHeaders) {
        this._pathPattern = Regexs.safeCompilePattern(pathPattern);
        this._methodPattern = Regexs.safeCompilePattern(methodPattern);
        this._responseStatus = responseStatus;
        this._extraHeaders = extraHeaders;
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
                            if (isMatch(_pathPattern, req.uri())
                                && isMatch(_methodPattern, req.method().name())) {
                                return io4Response(io, req);
                            } else {
                                //  not handle this trade
                                return null;
                            }
                        }
                    }})
                .toSingle();
    }

    private static boolean isMatch(final Pattern pattern, final String str) {
        return (null==pattern) || (null!=pattern && pattern.matcher(str).find());
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
                    .delaySubscription(originalio.inbound().ignoreElements());
            }};
    }
    
    private final Pattern _pathPattern;
    private final Pattern _methodPattern;
    private final int _responseStatus;
    private final Map<String, String> _extraHeaders;
}
