/**
 * 
 */
package org.jocean.xharbor.route;

import java.util.Arrays;
import java.util.List;

import org.apache.curator.framework.CuratorFramework;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.annotation.JSONField;

/**
 * @author isdom
 *
 */
public class RouteUtils {
    private static final Logger LOG = LoggerFactory
            .getLogger(RouteUtils.class);

    public static RoutingRules buildRoutingRulesFromZK(final CuratorFramework client, final String path) 
            throws Exception {
        final RoutingRulesImpl routingRules = new RoutingRulesImpl();
        final List<String> levels = client.getChildren().forPath(path);
        for ( String priority : levels ) {
            try {
                addRules(client, routingRules, path + "/" + priority, Integer.parseInt(priority));
            }
            catch (NumberFormatException e) {
                LOG.warn("invalid priority for can't convert to integer, detail: {}", 
                        ExceptionUtils.exception2detail(e));
            }
        }
        return routingRules;
    }

    private static void addRules(
            final CuratorFramework client, 
            final RoutingRulesImpl routingRules,
            final String pathToLevel,
            final int priority) throws Exception {
        final List<String> hosts = client.getChildren().forPath(pathToLevel);
        for ( String host : hosts ) {
            final Pair<String, String[]> rule = 
                    generateRule(client, pathToLevel + "/" + host, host);
            if ( null != rule ) {
                try {
                    routingRules.addRule(priority, rule.getFirst(), rule.getSecond());
                } catch (Exception e) {
                    LOG.warn("exception when add rule({}/{}) for level({}), detail:{}",
                            rule.getFirst(), Arrays.toString(rule.getSecond()), 
                            pathToLevel, ExceptionUtils.exception2detail(e));
                }
            }
        }
    }

    public static class RuleDesc {
        
        private String descrption;
        private String scheme;
        private String[] regexs;
        
        @JSONField(name="descrption")
        public String getDescrption() {
            return descrption;
        }

        @JSONField(name="descrption")
        public void setDescrption(String descrption) {
            this.descrption = descrption;
        }

        @JSONField(name="scheme")
        public String getScheme() {
            return scheme;
        }
        
        @JSONField(name="scheme")
        public void setScheme(String scheme) {
            this.scheme = scheme;
        }
        
        @JSONField(name="regexs")
        public String[] getRegexs() {
            return regexs;
        }
        
        @JSONField(name="regexs")
        public void setRegexs(String[] patterns) {
            this.regexs = patterns;
        }
    }
    
    private static Pair<String, String[]> generateRule(
            final CuratorFramework client, 
            final String pathToHost, 
            final String host)
            throws Exception {
        final byte[] content = client.getData().forPath(pathToHost);
        if ( content != null && content.length > 0) {
            final RuleDesc desc = JSON.parseObject(new String(content,  "UTF-8"), RuleDesc.class);
            if ( null != desc ) {
                LOG.debug("generateRule for {}/{}", desc.getScheme(), Arrays.toString( desc.getRegexs() ) );
                return Pair.of(desc.getScheme() + "://" + host, desc.getRegexs());
            }
        }
        return null;
    }
}
