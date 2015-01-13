package org.jocean.xharbor.route;

import java.util.Arrays;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.TreeCache;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.apache.curator.framework.recipes.cache.TreeCacheListener;
import org.jocean.event.api.AbstractFlow;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.EventReceiverSource;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Pair;
import org.jocean.idiom.Visitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.annotation.JSONField;

public class RulesZKUpdater {
    private static final Logger LOG = LoggerFactory
            .getLogger(RulesZKUpdater.class);

    public RulesZKUpdater(
            final EventReceiverSource source,
            final CuratorFramework client, 
            final String root, 
            final Visitor<RoutingInfo2URIs> updateRules) {
        this._updateRules = updateRules;
        this._root = root;
        this._zkCache = TreeCache.newBuilder(client, root).setCacheData(true).build();
        this._rules = new RoutingInfo2URIs();
        this._receiver = new ZKTreeWatcherFlow() {{
            source.create(this, this.UNINITIALIZED);
        }}.queryInterfaceInstance(EventReceiver.class);
        this._zkCache.getListenable().addListener(new TreeCacheListener() {

            @Override
            public void childEvent(CuratorFramework client, TreeCacheEvent event)
                    throws Exception {
                _receiver.acceptEvent(event.getType().name(), event);
            }});
        try {
            this._zkCache.start();
        } catch (Exception e) {
            LOG.error("exception when TreeCache({})'s start, detail:{}", 
                    this._zkCache, ExceptionUtils.exception2detail(e));
        }
    }

    private class ZKTreeWatcherFlow extends AbstractFlow<ZKTreeWatcherFlow> {
        final BizStep UNINITIALIZED = new BizStep("updaterules.UNINITIALIZED") {

            @OnEvent(event = "NODE_ADDED")
            private BizStep nodeAdded(final TreeCacheEvent event) throws Exception {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("handler ({}) with event ({}), try to add or update rule", 
                            currentEventHandler(), event);
                }
                try {
                    addOrUpdateToRules(_rules, event);
                } catch (Exception e) {
                    LOG.warn("exception when addOrUpdateRules for event({}), detail:{}",
                            event, ExceptionUtils.exception2detail(e));
                }
                
                return currentEventHandler();
            }
            
            @OnEvent(event = "NODE_REMOVED")
            private BizStep nodeRemoved(final TreeCacheEvent event) throws Exception {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("handler ({}) with event ({}), try to remove rule", 
                            currentEventHandler(), event);
                }
                try {
                    removeFromRules(_rules, event);
                } catch (Exception e) {
                    LOG.warn("exception when removeFromRules for event({}), detail:{}",
                            event, ExceptionUtils.exception2detail(e));
                }
                
                return currentEventHandler();
            }
            
            @OnEvent(event = "NODE_UPDATED")
            private BizStep nodeUpdated(final TreeCacheEvent event) throws Exception {
                return nodeAdded(event);
            }
            
            @OnEvent(event = "INITIALIZED")
            private BizStep initialized(final TreeCacheEvent event) throws Exception {
                updateRules(_rules);
                return INITIALIZED;
            }
        }
        .freeze();
        
        final BizStep INITIALIZED = new BizStep("updaterules.INITIALIZED") {

            @OnEvent(event = "NODE_ADDED")
            private BizStep nodeAdded(final TreeCacheEvent event) throws Exception {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("handler ({}) with event ({}), try to add or update rule", 
                            currentEventHandler(), event);
                }
                try {
                    updateRules(addOrUpdateToRules(_rules, event));
                } catch (Exception e) {
                    LOG.warn("exception when addOrUpdateRules for event({}), detail:{}",
                            event, ExceptionUtils.exception2detail(e));
                }
                
                return currentEventHandler();
            }
            
            @OnEvent(event = "NODE_REMOVED")
            private BizStep nodeRemoved(final TreeCacheEvent event) throws Exception {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("handler ({}) with event ({}), try to remove rule", 
                            currentEventHandler(), event);
                }
                try {
                    updateRules(removeFromRules(_rules, event));
                } catch (Exception e) {
                    LOG.warn("exception when removeFromRules for event({}), detail:{}",
                            event, ExceptionUtils.exception2detail(e));
                }
                
                return currentEventHandler();
            }
            
            @OnEvent(event = "NODE_UPDATED")
            private BizStep nodeUpdated(final TreeCacheEvent event) throws Exception {
                return nodeAdded(event);
            }
        }
        .freeze();
    }
    
    private RoutingInfo2URIs addOrUpdateToRules(
            final RoutingInfo2URIs rules, 
            final TreeCacheEvent event) throws Exception {
        final ChildData data = event.getData();
        final Pair<Integer,String> pair = parseFromPath(data.getPath());
        final RuleDesc desc = parseFromData(data.getData());
        if (null != pair && null != desc ) {
            final int priority = pair.getFirst();
            final String uri = pair.getSecond().replace(":||","://");
            if ( LOG.isDebugEnabled()) {
                LOG.debug("add or update rule with {}/{}/{}", 
                        priority, uri, Arrays.toString( desc.getRegexs()));
            }
            return rules.addOrUpdateRule(priority, uri, desc.getRegexs());
        }
        return null;
    }
    
    private RoutingInfo2URIs removeFromRules(
            final RoutingInfo2URIs rules, 
            final TreeCacheEvent event) throws Exception {
        final ChildData data = event.getData();
        final Pair<Integer,String> pair = parseFromPath(data.getPath());
        if (null != pair ) {
            final int priority = pair.getFirst();
            final String uri = pair.getSecond().replace(":||","://");
            if ( LOG.isDebugEnabled()) {
                LOG.debug("remove rule with {}/{}", priority, uri);
            }
            return rules.removeRule(pair.getFirst(), pair.getSecond().replace(":||", "://"));
        }
        return null;
    }
    
    private void updateRules(final RoutingInfo2URIs newRules) {
        if ( null != newRules ) {
            this._rules = newRules;
            try {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("try to update rules for ({})", newRules);
                }
                this._updateRules.visit(this._rules.freeze());
            } catch (Exception e) {
                LOG.warn("exception when update rules {} via ({}), detail:{}",
                        newRules, this._updateRules, ExceptionUtils.exception2detail(e));
            }
        }
    }
    
    public static class RuleDesc {
        
        public static class InfoDesc implements RoutingInfo {

            public String method;
            public String path;
            
            @Override
            public String getMethod() {
                return method;
            }

            @Override
            public String getPath() {
                return path;
            }
        }
        
        private String descrption;
        private String scheme;
        private InfoDesc[] regexs;
        
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
        public InfoDesc[] getRegexs() {
            return regexs;
        }
        
        @JSONField(name="regexs")
        public void setRegexs(InfoDesc[] regexs) {
            this.regexs = regexs;
        }
    }
    
    private Pair<Integer, String> parseFromPath(final String path) {
        if (path.length() <= this._root.length() ) {
            return null;
        }
        final String pathToHost = path.substring(_root.length() + ( !this._root.endsWith("/") ? 1 : 0 ));
        final String[] arrays = pathToHost.split("/");
        if ( arrays.length != 2 ) {
            return null;
        }
        try {
            return Pair.of(Integer.parseInt(arrays[0]), arrays[1]);
        }
        catch (Exception e) {
            LOG.warn("exception when parse from path {}, detail:{}", 
                    path, ExceptionUtils.exception2detail(e));
        }
        return null;
    }

    private RuleDesc parseFromData(final byte[] data) {
        if (null == data || data.length == 0) {
            return null;
        }
        try {
            return JSON.parseObject(new String(data,  "UTF-8"), RuleDesc.class);
        } catch (Exception e) {
            LOG.warn("exception when parse from data {}, detail:{}", 
                    Arrays.toString(data), ExceptionUtils.exception2detail(e));
        }
        return null;
    }

    private final String _root;
    private final TreeCache _zkCache;
    private final Visitor<RoutingInfo2URIs> _updateRules;
    private RoutingInfo2URIs _rules;
    private final EventReceiver _receiver;
}
