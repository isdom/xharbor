/**
 * 
 */
package org.jocean.xharbor.transform;

import io.netty.handler.codec.http.HttpRequest;

import java.util.HashMap;
import java.util.Map;

import org.jocean.xharbor.spi.HttpRequestTransformer;
import org.jocean.xharbor.spi.HttpRequestTransformer.Builder;

/**
 * @author isdom
 *
 */
public class CompositeAUPBuilder implements Builder, Cloneable {

    @Override
    protected CompositeAUPBuilder clone() throws CloneNotSupportedException {
        final CompositeAUPBuilder cloned = new CompositeAUPBuilder();
        for ( ApplyUrlencode4Post transformer : this._builders.values() ) {
            cloned._builders.put(transformer.path(), transformer.clone());
        }
        return cloned;
    }
    
    @Override
    public HttpRequestTransformer build(final HttpRequest httpRequest) {
        for (HttpRequestTransformer.Builder builder : this._builders.values()) {
            final HttpRequestTransformer transformer = builder.build(httpRequest);
            if (null != transformer) {
                return transformer;
            }
        }
        return null;
    }
    
    public CompositeAUPBuilder freeze() {
        this._isFrozen = true;
        return  this;
    }

    public CompositeAUPBuilder addOrUpdateAUP(
            final String path, 
            final String noapplyFeature,
            final String[] applyKeys) throws Exception {
        if ( !this._isFrozen ) {
            this._builders.put(path, new ApplyUrlencode4Post(path, noapplyFeature, applyKeys));
            return  this;
        } else {
            return this.clone().addOrUpdateAUP(path, noapplyFeature, applyKeys);
        }
    }
    
    public CompositeAUPBuilder removeAUP(final String path)
            throws Exception {
        if ( !this._isFrozen ) {
            this._builders.remove(path);
            return  this;
        } else {
            return this.clone().removeAUP(path);
        }
    }

    private final Map<String, ApplyUrlencode4Post> _builders = 
            new HashMap<String, ApplyUrlencode4Post>();
    private transient boolean _isFrozen = false;
}
