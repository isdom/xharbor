package org.jocean.xharbor.routing.impl;

import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.Map;
import java.util.regex.Pattern;

import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.routing.RespondRule;
import org.jocean.xharbor.routing.RuleSet;

import rx.functions.Func1;

public class HeaderonlyRespond implements RespondRule {
    public HeaderonlyRespond(
            final RuleSet rules,
            final String methodPattern, 
            final String pathPattern, 
            final int responseStatus, 
            final Map<String, String> extraHeaders) {
        this._rules = rules;
        this._pathPattern = safeCompilePattern(pathPattern);
        this._methodPattern = safeCompilePattern(methodPattern);
        this._responseStatus = responseStatus;
        this._extraHeaders = extraHeaders;
        
        this._rules.addRespond(this);
    }
    
    public void stop() {
        this._rules.removeRespond(this);
    }
    
    @Override
    public Func1<HttpRequest, FullHttpResponse> genResponser(final RoutingInfo info) {
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
        
    private final RuleSet _rules;
    private final Pattern _pathPattern;
    private final Pattern _methodPattern;
    private final int _responseStatus;
    private final Map<String, String> _extraHeaders;
}
