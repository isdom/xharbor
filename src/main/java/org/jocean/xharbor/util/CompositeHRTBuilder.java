/**
 * 
 */
package org.jocean.xharbor.util;

import io.netty.handler.codec.http.HttpRequest;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jocean.http.HttpRequestTransformer;
import org.jocean.http.HttpRequestTransformer.Builder;

/**
 * @author isdom
 *
 */
public class CompositeHRTBuilder implements Builder {

    @Override
    public HttpRequestTransformer build(final HttpRequest httpRequest) {
        final Iterator<HttpRequestTransformer.Builder> iter = this._builders.iterator();
        while (iter.hasNext()) {
            final HttpRequestTransformer transformer = iter.next().build(httpRequest);
            if (null != transformer) {
                return transformer;
            }
        }
        return null;
    }
    
    public void addBuilder(final HttpRequestTransformer.Builder builder) {
        this._builders.add(builder);
    }
    
    public void removeBuilder(final HttpRequestTransformer.Builder builder) {
        this._builders.remove(builder);
    }
    
    private final List<HttpRequestTransformer.Builder> _builders = 
            new CopyOnWriteArrayList<HttpRequestTransformer.Builder>();
}
