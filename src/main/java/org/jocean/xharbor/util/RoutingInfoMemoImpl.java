/**
 * 
 */
package org.jocean.xharbor.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.idiom.SimpleCache;
import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.api.RoutingInfoMemo;

import rx.functions.Func1;

/**
 * @author isdom
 *
 */
public class RoutingInfoMemoImpl implements RoutingInfoMemo {

    @Override
    public void incRoutingInfo(final RoutingInfo info) {
        this._cache.get(info).incrementAndGet();
    }

    public String[] getRoutingInfos() {
        return new ArrayList<String>() {
            private static final long serialVersionUID = 1L;
        {
            final Iterator<Map.Entry<RoutingInfo, AtomicInteger>> itr = _cache.snapshot().entrySet().iterator();
            while (itr.hasNext()) {
                final Map.Entry<RoutingInfo, AtomicInteger> entry = itr.next();
                this.add(entry.getKey() + "-->" + entry.getValue().get());
            }
        }}.toArray(new String[0]);
    }

    private final SimpleCache<RoutingInfo, AtomicInteger> _cache = 
            new SimpleCache<RoutingInfo, AtomicInteger>(
                new Func1<RoutingInfo, AtomicInteger>() {
                    @Override
                    public AtomicInteger call(final RoutingInfo input) {
                        return new AtomicInteger(0);
                    }
                });
}
