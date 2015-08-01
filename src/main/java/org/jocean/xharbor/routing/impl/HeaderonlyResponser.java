package org.jocean.xharbor.routing.impl;

import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.Map;
import java.util.regex.Pattern;

import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.routing.Responser;
import org.jocean.xharbor.routing.RouteLevel;

import rx.functions.Func1;

public class HeaderonlyResponser implements Responser {
    public HeaderonlyResponser(
            final RouteLevel level,
            final String methodPattern, 
            final String pathPattern, 
            final int responseStatus, 
            final Map<String, String> extraHeaders) {
        this._level = level;
        this._pathPattern = safeCompilePattern(pathPattern);
        this._methodPattern = safeCompilePattern(methodPattern);
        this._responseStatus = responseStatus;
        this._extraHeaders = extraHeaders;
        
        this._level.addResponser(this);
    }
    
    public void stop() {
        this._level.removeResponser(this);
    }
    
    @Override
    public Func1<HttpRequest, FullHttpResponse> genResponseBuilder(final RoutingInfo info) {
        if (isMatch(this._pathPattern, info.getPath())
           && isMatch(this._methodPattern, info.getMethod())) {
            return _func1;
        } else {
            return null;
        }
    }

    private static boolean isMatch(final Pattern pattern, final String str) {
        return (null==pattern) || (null!=pattern && pattern.matcher(str).find());
    }
    
    private static Pattern safeCompilePattern(final String regex) {
        return null != regex && !"".equals(regex) ? Pattern.compile(regex) : null;
    }
    
    final Func1<HttpRequest, FullHttpResponse> _func1 = new Func1<HttpRequest, FullHttpResponse>() {

        @Override
        public FullHttpResponse call(final HttpRequest request) {
            final FullHttpResponse response = new DefaultFullHttpResponse(
                    request.getProtocolVersion(), HttpResponseStatus.valueOf(_responseStatus));
            HttpHeaders.setHeader(response, HttpHeaders.Names.CONTENT_LENGTH, 0);
            if (null!=_extraHeaders) {
                for (Map.Entry<String, String> entry : _extraHeaders.entrySet()) {
                    response.headers().set(entry.getKey(), entry.getValue());
                }
            }
            return response;
        }};
        
    private final RouteLevel _level;
    private final Pattern _pathPattern;
    private final Pattern _methodPattern;
    private final int _responseStatus;
    private final Map<String, String> _extraHeaders;
}
