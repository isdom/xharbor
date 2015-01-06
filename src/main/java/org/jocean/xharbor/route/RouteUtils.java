/**
 * 
 */
package org.jocean.xharbor.route;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.curator.framework.CuratorFramework;
import org.jocean.event.api.EventReceiverSource;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Pair;
import org.jocean.j2se.MBeanRegisterSupport;
import org.jocean.xharbor.spi.Router;
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

    public interface PathMXBean {
        public String[] getRoutes();
    }
    
    public static Router<String, URI[]> buildCachedPathRouter(final String prefix, final EventReceiverSource source) {
        final MBeanRegisterSupport routerMbeanSupport = 
                new MBeanRegisterSupport(prefix, null);

        final MBeanRegisterSupport pathMBeanSupport =
                new MBeanRegisterSupport(prefix, null);
      
        return new CachedRouter<String, URI[]>(source, 
                new CachedRouter.OnRouterUpdated<String, URI[]>() {
                    @Override
                    public void visit(final Router<String, URI[]> prevImpl, final Router<String, URI[]> newImpl)
                            throws Exception {
                      if ( null != prevImpl ) {
                          routerMbeanSupport.unregisterMBean("name=routerImpl");
                      }
                      routerMbeanSupport.registerMBean("name=routerImpl", newImpl);
                      pathMBeanSupport.unregisterAllMBeans();
                        
                    }}, 
                new CachedRouter.OnRouted<String, URI[]>() {
                    @Override
                    public void visit(final String path, final URI[] uris) throws Exception {
                        if (!pathMBeanSupport.isRegistered("path=" + path)) {
                            final String[] routesAsStringArray = new ArrayList<String>() {
                                private static final long serialVersionUID = 1L;
                                {
                                    for (URI uri : uris) {
                                        this.add(uri.toString());
                                    }
                                }
                            }.toArray(new String[0]);
                            pathMBeanSupport.registerMBean("path=" + path,
                                    new PathMXBean() {
                                        @Override
                                        public String[] getRoutes() {
                                            return routesAsStringArray;
                                        }
                                    });
                        }
                        
                    }});
    }
    
    public static Router<String, URI[]> buildPathRouterFromZK(final CuratorFramework client, final String path) 
            throws Exception {
        final Path2URIsRouter router = new Path2URIsRouter();
        final List<String> levels = client.getChildren().forPath(path);
        for ( String priority : levels ) {
            try {
                addRules(client, router, path + "/" + priority, Integer.parseInt(priority));
            }
            catch (NumberFormatException e) {
                LOG.warn("invalid priority for can't convert to integer, detail: {}", 
                        ExceptionUtils.exception2detail(e));
            }
        }
        return router;
    }

    private static void addRules(
            final CuratorFramework client, 
            final Path2URIsRouter router,
            final String pathToLevel,
            final int priority) throws Exception {
        final List<String> hosts = client.getChildren().forPath(pathToLevel);
        for ( String host : hosts ) {
            final Pair<String, String[]> rule = 
                    generateRule(client, pathToLevel + "/" + host, host);
            if ( null != rule ) {
                try {
                    router.addRule(priority, rule.getFirst(), rule.getSecond());
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
