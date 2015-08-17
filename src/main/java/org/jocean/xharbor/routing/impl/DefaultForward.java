package org.jocean.xharbor.routing.impl;

import java.net.URI;
import java.util.regex.Pattern;

import org.jocean.http.Feature;
import org.jocean.http.util.FeaturesBuilder;
import org.jocean.idiom.BeanHolder;
import org.jocean.idiom.BeanHolderAware;
import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.api.Target;
import org.jocean.xharbor.routing.ForwardRule;
import org.jocean.xharbor.routing.RuleSet;

import rx.functions.Func0;

public class DefaultForward implements ForwardRule, BeanHolderAware {
    public DefaultForward(final RuleSet level, 
            final String uri,
            final String featuresName,
            final String methodPattern, 
            final String pathPattern) throws Exception {
        this._level = level;
        this._uri = new URI(uri);
        this._featuresName = featuresName;
        this._methodPattern = safeCompilePattern(methodPattern);
        this._pathPattern = safeCompilePattern(pathPattern);
        
        this._level.addForward(this);
    }
    
    public void stop() {
        this._level.removeForward(this);
    }
    
    @Override
    public void setBeanHolder(final BeanHolder beanHolder) {
        this._beanHolder = beanHolder;
    }
    
    public Target match(final RoutingInfo info) {
        return ( isMatched(this._methodPattern, info.getMethod()) 
                && isMatched(this._pathPattern, info.getPath()) ) 
             ? new Target() {
                @Override
                public URI serviceUri() {
                    return _uri ;
                }
                @Override
                public Func0<Feature[]> features() {
                    final FeaturesBuilder builder = _beanHolder.getBean(_featuresName, FeaturesBuilder.class);
                    return builder;
                }
                @Override
                public String toString() {
                    return "[uri:" + _uri+ ",features:" + _featuresName + "]";
                }}
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
    private BeanHolder _beanHolder;
    private final String _featuresName;
}
