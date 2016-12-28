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
            for (Map.Entry<String, Func0<Map<String, Object>>> entry : _register.entrySet()) {
                this.put(entry.getKey(), entry.getValue().call());
            }
        }};
    }
    
    @Override
    public void call(final String name, final Func0<Map<String, Object>> getter) {
        this._register.put(name, getter);
    }

    private final Map<String, Func0<Map<String, Object>>> _register = 
            new ConcurrentHashMap<>();

}
