package org.jocean.xharbor.routing.impl;

import io.netty.handler.codec.http.HttpRequest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jocean.xharbor.routing.RequestRewriter;
import org.jocean.xharbor.routing.RouteLevel;

import rx.functions.Action1;

public class PathRewriter implements RequestRewriter {
    public PathRewriter(
            final RouteLevel level,
            final String pathPattern, 
            final String replaceTo) {
        this._level = level;
        this._pathPattern = safeCompilePattern(pathPattern);
        this._replaceTo = replaceTo;
        this._level.addRequestRewriter(this);
    }
    
    public void stop() {
        this._level.removeRequestRewriter(this);
    }
    
    @Override
    public Action1<HttpRequest> genRewriting(final String path) {
        final Matcher matcher = this._pathPattern.matcher(path);
        if ( matcher.find() ) {
            return new Action1<HttpRequest>() {
                @Override
                public void call(final HttpRequest request) {
                    request.setUri(
                        _pathPattern.matcher(request.getUri())
                            .replaceFirst(_replaceTo));
                }
                
                @Override
                public String toString() {
                    return "[" + _pathPattern.toString() + "->" + _replaceTo + "]";
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
    private final String _replaceTo;
}
