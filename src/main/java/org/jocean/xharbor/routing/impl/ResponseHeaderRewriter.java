package org.jocean.xharbor.routing.impl;

import io.netty.handler.codec.http.HttpResponse;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jocean.xharbor.routing.ResponseRewriter;
import org.jocean.xharbor.routing.RouteLevel;

import rx.functions.Action1;

public class ResponseHeaderRewriter implements ResponseRewriter {
    public ResponseHeaderRewriter(
            final RouteLevel level,
            final String pathPattern,
            final Map<String, String> extraHeaders) {
        this._level = level;
        this._pathPattern = safeCompilePattern(pathPattern);
        this._extraHeaders = extraHeaders;
        this._level.addResponseRewriter(this);
    }
    
    public void stop() {
        this._level.removeResponseRewriter(this);
    }
    
    @Override
    public Action1<HttpResponse> genRewriting(final String path) {
        final Matcher matcher = this._pathPattern.matcher(path);
        if ( matcher.find() ) {
            return new Action1<HttpResponse>() {
                @Override
                public void call(final HttpResponse response) {
                    for (Map.Entry<String, String> entry : _extraHeaders.entrySet()) {
                        response.headers().add(entry.getKey(), entry.getValue());
                    }
                }
                
                @Override
                public String toString() {
                    return "[" + _pathPattern.toString() + "-> headers ]";
                }};
        } else {
            return null;
        }
    }
    
    private static Pattern safeCompilePattern(final String regex) {
        return null != regex && !"".equals(regex) ? Pattern.compile(regex) : null;
    }
    
    private final RouteLevel _level;
    private final Pattern _pathPattern;
    private final Map<String, String> _extraHeaders;
}
