/**
 * 
 */
package org.jocean.xharbor.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jocean.idiom.Visitor2;
import org.jocean.j2se.stats.InfoListMaker;

/**
 * @author isdom
 *
 */
public class StatsImpl implements RulesMXBean, Visitor2<String,InfoListMaker> {

    @Override
    public String[] getRoutingRules() {
        return new ArrayList<String>() {
            private static final long serialVersionUID = 1L;
        {
            for (Map.Entry<String, InfoListMaker> entry : _register.entrySet()) {
                final List<String> infos = new ArrayList<String>();
                entry.getValue().addInfoList(infos);
                final StringBuilder sb = new StringBuilder();
                sb.append(entry.getKey());
                sb.append('\t');
                sb.append("-->");
                for ( String s : infos) {
                    sb.append('\n');
                    sb.append('\t');
                    sb.append(s);
                }
                this.add(sb.toString());
            }
            Collections.sort(this);
        }}.toArray(new String[0]);
    }
    
    @Override
    public void visit(final String name, InfoListMaker maker) throws Exception {
//      this._mbeanSupport.registerMBean(name, maker.createMBean());
        this._register.put(name, maker);
    }

    private final Map<String, InfoListMaker> _register = 
            new ConcurrentHashMap<String, InfoListMaker>();

//  private final MBeanRegisterSupport _mbeanSupport = 
//            new MBeanRegisterSupport("org.jocean:type=router", null);

}
