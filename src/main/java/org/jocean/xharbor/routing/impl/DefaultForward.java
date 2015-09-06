package org.jocean.xharbor.routing.impl;

import java.net.URI;
import java.util.regex.Pattern;

import org.jocean.http.Feature;
import org.jocean.http.util.FeaturesBuilder;
import org.jocean.idiom.BeanHolder;
import org.jocean.idiom.BeanHolderAware;
import org.jocean.idiom.Regexs;
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
            final String pathPattern,
            final String xroutePattern
            ) throws Exception {
        this._rules = rules;
        this._uri = new URI(uri);
        this._featuresName = featuresName;
        this._methodPattern = Regexs.safeCompilePattern(methodPattern);
        this._pathPattern = Regexs.safeCompilePattern(pathPattern);
        this._xroutePattern = Regexs.safeCompilePattern(xroutePattern);
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
        return ( Regexs.isMatched(this._methodPattern, info.getMethod()) 
                && Regexs.isMatched(this._pathPattern, info.getPath()) 
                && Regexs.isMatched(this._xroutePattern, info.getXRouteCode()) ) 
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
    
    @Override
    public String toString() {
        return "[forward(METHOD=" + _methodPattern 
                + "/PATH=" + _pathPattern
                + ")-->" + _uri + "]";
    }

    private final RuleSet _rules;
    private final Pattern _methodPattern;
    private final Pattern _pathPattern;
    private final Pattern _xroutePattern;
    private final URI _uri;
    private BeanHolder _beanHolder;
    private final String _featuresName;
}
