/**
 * 
 */
package org.jocean.xharbor.util;

import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jocean.idiom.Function;
import org.jocean.idiom.SimpleCache;

/**
 * @author isdom
 *
 */
public class ServiceMemoImpl implements ServiceMemo {
    @Override
    public boolean isServiceDown(final URI uri) {
        return this._statusCache.get(uri).get();
    }

    @Override
    public void markServiceDownStatus(final URI uri, final boolean isDown) {
        this._statusCache.get(uri).set(isDown);
    }

    public void resetAll() {
        this._statusCache.clear();
    }
    
    public String[] getAllServiceStatus() {
        return new ArrayList<String>() {
            private static final long serialVersionUID = 1L;
        {
            final Iterator<Map.Entry<URI, AtomicBoolean>> itr = _statusCache.snapshot().entrySet().iterator();
            while (itr.hasNext()) {
                final Map.Entry<URI, AtomicBoolean> entry = itr.next();
                this.add(entry.getKey() + ":(" + entry.getValue() + ")");
            }
        }}.toArray(new String[0]);
    }
    
    private final SimpleCache<URI, AtomicBoolean> _statusCache = 
        new SimpleCache<URI, AtomicBoolean>(
            new Function<URI, AtomicBoolean>() {
                @Override
                public AtomicBoolean apply(final URI input) {
                    return new AtomicBoolean(false);
                }
            });
}
