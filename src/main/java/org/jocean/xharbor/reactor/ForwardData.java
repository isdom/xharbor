package org.jocean.xharbor.reactor;

import java.net.URI;

import org.jocean.http.Feature;
import org.jocean.http.util.FeaturesBuilder;
import org.jocean.idiom.BeanHolder;
import org.jocean.idiom.BeanHolderAware;
import org.jocean.xharbor.api.Target;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.functions.Func0;

public class ForwardData implements BeanHolderAware {
    
    private static final Logger LOG = LoggerFactory
            .getLogger(ForwardData.class);
    
    public ForwardData(
            final MatchRule matcher,
            final String uri,
            final String featuresName
            ) throws Exception {
        this._matcher = matcher;
        this._uri = new URI(uri);
        this._featuresName = featuresName;
    }
    
    MatchRule matcher() {
        return this._matcher;
    }
    
    Target target() {
        return new Target() {
            @Override
            public URI serviceUri() {
                return _uri ;
            }
            @Override
            public Func0<Feature[]> features() {
                final FeaturesBuilder builder = _beanHolder.getBean(_featuresName, FeaturesBuilder.class);
                if (null==builder) {
                    LOG.warn("forward rule {} require FeaturesBuilder named({}) not exist! please check xharbor config!",
                            ForwardData.this, _featuresName);
                }
                return null!=builder ? builder : Feature.FEATURESBUILDER_FOR_EMPTY;
            }
            @Override
            public String toString() {
                return "[uri:" + _uri+ ",features:" + _featuresName + "]";
            }};
    }
    
    @Override
    public void setBeanHolder(final BeanHolder beanHolder) {
        this._beanHolder = beanHolder;
    }
    
    private BeanHolder _beanHolder;
    
    private final MatchRule _matcher;
    private final URI _uri;
    private final String _featuresName;
}
