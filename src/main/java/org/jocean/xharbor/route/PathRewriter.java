package org.jocean.xharbor.route;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jocean.idiom.Function;

public class PathRewriter {
    public PathRewriter(
            final Level level,
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
    
    public Function<String, String> genRewriter(final String path) {
        final Matcher matcher = this._pathPattern.matcher(path);
        if ( matcher.find() ) {
            return new Function<String, String>() {
                @Override
                public String apply(final String input) {
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
    
    private final Level _level;
    private final Pattern _pathPattern;
    private final String _replaceTo;
}
