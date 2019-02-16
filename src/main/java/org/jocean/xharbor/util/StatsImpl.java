/**
 *
 */
package org.jocean.xharbor.util;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import rx.functions.Action2;
import rx.functions.Func0;

/**
 * @author isdom
 *
 */
public class StatsImpl implements RulesMBean, Action2<String, Func0<Map<String, Object>>> {

    @Override
    public Map<String, Map<String, Object>> getRoutingRules() {
        return new HashMap<String, Map<String, Object>>() {
            private static final long serialVersionUID = 1L;
        {
            for (final Map.Entry<String, Func0<Map<String, Object>>> entry : _register.entrySet()) {
                final Map<String, Object> content = entry.getValue().call();
                if (null != content && !content.isEmpty()) {
                    this.put(entry.getKey(), content);
                }
            }
        }};
    }

    @Override
    public void call(final String name, final Func0<Map<String, Object>> getter) {
        this._register.put(name, getter);
    }

    private final Map<String, Func0<Map<String, Object>>> _register = new ConcurrentHashMap<>();
}
