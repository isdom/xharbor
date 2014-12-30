/**
 * 
 */
package org.jocean.httpgateway.route;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.curator.framework.CuratorFramework;
import org.jocean.httpgateway.route.RoutingRulesImpl.Level;
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
        final List<String> children = client.getChildren().forPath(path);
        for ( String child : children ) {
            try {
                final int priority = Integer.parseInt(child);
                final Level level = generateLevel(client, path + "/" + child, priority);
                if ( null != level ) {
                    routingRules.addLevel(level);
                }
            }
            catch (NumberFormatException e) {
                LOG.warn("invalid priority for can't convert to integer, detail: {}", 
                        ExceptionUtils.exception2detail(e));
            }
        }
        return routingRules;
    }

    private static Level generateLevel(final CuratorFramework client, final String pathToLevel,
            final int priority) throws Exception {
        final List<Pair<URI, Pattern[]>> rules = new ArrayList<Pair<URI, Pattern[]>>();
        final List<String> hosts = client.getChildren().forPath(pathToLevel);
        for ( String host : hosts ) {
            final Pair<URI, Pattern[]> rule = 
                    generateRule(client, pathToLevel + "/" + host, host);
            if ( null != rule ) {
                rules.add(rule);
            }
        }
        if ( !rules.isEmpty() ) {
            final Level level = new Level(priority);
            for (Pair<URI, Pattern[]> target : rules ) {
                level.addRule(target.getFirst(), target.getSecond());
            }
            LOG.debug("generateRuleSet for path:{}, priority:{}", pathToLevel, priority );
            return level;
        }
        else {
            return null;
        }
    }

    public static class Rule {
        
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
        
        public Pattern[] asPatternArray() {
            final List<Pattern> patterns = new ArrayList<Pattern>();
            
            for ( String regex : this.regexs) {
                patterns.add(Pattern.compile(regex));
            }
            return patterns.toArray(new Pattern[0]);
        }
    }
    
    private static Pair<URI, Pattern[]> generateRule(final CuratorFramework client, final String pathToHost, final String host) 
            throws Exception {
        final byte[] content = client.getData().forPath(pathToHost);
        if ( content != null && content.length > 0) {
            final Rule target = JSON.parseObject(new String(content,  "UTF-8"), Rule.class);
            if ( null != target ) {
                LOG.debug("generateRule for {}/{}", target.getScheme(), Arrays.toString( target.getRegexs() ) );
                return Pair.of(new URI(target.getScheme() + "://" + host), target.asPatternArray());
            }
        }
        return null;
    }
}
