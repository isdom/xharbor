package org.jocean.xharbor.routing.impl;

import java.net.URI;
import java.util.regex.Pattern;

import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.routing.RuleSet;
import org.jocean.xharbor.routing.ForwardRule;

public class DefaultForward implements ForwardRule {
    public DefaultForward(final RuleSet level, 
            final String uri,
            final String methodPattern, 
            final String pathPattern) throws Exception {
        this._level = level;
        this._uri = new URI(uri);
        this._methodPattern = safeCompilePattern(methodPattern);
        this._pathPattern = safeCompilePattern(pathPattern);
        
        this._level.addForward(this);
    }
    
    public void stop() {
        this._level.removeForward(this);
    }
    
    public URI match(final RoutingInfo info) {
        return ( isMatched(this._methodPattern, info.getMethod()) 
                && isMatched(this._pathPattern, info.getPath()) ) 
             ? this._uri 
             : null;
    }
    
    private static Pattern safeCompilePattern(final String regex) {
        return null != regex && !"".equals(regex) ? Pattern.compile(regex) : null;
    }
    
    private static boolean isMatched(final Pattern pattern, final String content) {
        return pattern != null ? pattern.matcher(content).find() : true;
    }
    
    @Override
    public String toString() {
        return "[uri=" + _uri
                + ":method=" + _methodPattern 
                + ",path=" + _pathPattern + "]";
    }

    private final RuleSet _level;
    private final URI _uri;
    private final Pattern _methodPattern;
    private final Pattern _pathPattern;
}
