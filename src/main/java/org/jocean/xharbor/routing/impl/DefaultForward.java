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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.functions.Func0;

public class DefaultForward implements ForwardRule, BeanHolderAware {
    
    private static final Logger LOG = LoggerFactory
            .getLogger(DefaultForward.class);
    
    public DefaultForward(final RuleSet rules, 
            final String uri,
            final String featuresName,
            final String methodPattern, 
            final String pathPattern) throws Exception {
        this._rules = rules;
        this._uri = new URI(uri);
        this._featuresName = featuresName;
        this._methodPattern = safeCompilePattern(methodPattern);
        this._pathPattern = safeCompilePattern(pathPattern);
        
        this._rules.addForward(this);
    }
    
    public void stop() {
        this._rules.removeForward(this);
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
                    if (null==builder) {
                        LOG.warn("forward rule {} require FeaturesBuilder named({}) not exist! please check xharbor config!",
                                DefaultForward.this, _featuresName);
                    }
                    return null!=builder ? builder : Feature.FEATURESBUILDER_FOR_EMPTY;
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
        return "[forward(METHOD=" + _methodPattern 
                + "/PATH=" + _pathPattern
                + ")-->" + _uri + "]";
    }

    private final RuleSet _rules;
    private final Pattern _methodPattern;
    private final Pattern _pathPattern;
    private final URI _uri;
    private BeanHolder _beanHolder;
    private final String _featuresName;
}
