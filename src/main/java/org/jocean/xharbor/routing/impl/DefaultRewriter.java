package org.jocean.xharbor.routing.impl;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jocean.xharbor.routing.PathRewriter;
import org.jocean.xharbor.routing.RouteLevel;

import rx.functions.Func1;

public class DefaultRewriter implements PathRewriter {
    public DefaultRewriter(
            final RouteLevel level,
            final String pathPattern, 
            final String replaceTo) {
        this._level = level;
        this._pathPattern = safeCompilePattern(pathPattern);
        this._replaceTo = replaceTo;
        this._level.addPathRewriter(this);
    }
    
    public void stop() {
        this._level.removePathRewriter(this);
    }
    
    @Override
    public Func1<String, String> genRewriting(final String path) {
        final Matcher matcher = this._pathPattern.matcher(path);
        if ( matcher.find() ) {
            return new Func1<String, String>() {
                @Override
                public String call(final String input) {
                    return _pathPattern.matcher(input).replaceFirst(_replaceTo);
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
