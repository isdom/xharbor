/**
 * 
 */
package org.jocean.xharbor.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jocean.idiom.Emitter;
import org.jocean.idiom.Visitor2;

import rx.functions.Action1;

/**
 * @author isdom
 *
 */
public class StatsImpl implements RulesMXBean, Visitor2<String,Emitter<String>> {

    @Override
    public String[] getRoutingRules() {
        return new ArrayList<String>() {
            private static final long serialVersionUID = 1L;
        {
            for (Map.Entry<String, Emitter<String>> entry : _register.entrySet()) {
                final StringBuilder sb = new StringBuilder();
                sb.append(entry.getKey());
                sb.append('\t');
                sb.append("-->");
                entry.getValue().emit(new Action1<String>() {
                    @Override
                    public void call(final String s) {
                        sb.append('\n');
                        sb.append('\t');
                        sb.append(s);
                    }});
                this.add(sb.toString());
            }
            Collections.sort(this);
        }}.toArray(new String[0]);
    }
    
    @Override
    public void visit(final String name, final Emitter<String> emitter) throws Exception {
        this._register.put(name, emitter);
    }

    private final Map<String, Emitter<String>> _register = 
            new ConcurrentHashMap<String, Emitter<String>>();

}
