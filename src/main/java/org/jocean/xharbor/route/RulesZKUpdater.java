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
        this._receiver = new UpdateImplFlow() {{
            source.create(this, this.UPDATE);
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

    private class UpdateImplFlow extends AbstractFlow<UpdateImplFlow> {
        final BizStep UPDATE = new BizStep("routingrules.UPDATE") {

            @OnEvent(event = "NODE_ADDED")
            private BizStep nodeAdded(final TreeCacheEvent event) throws Exception {
                final ChildData data = event.getData();
                final Pair<Integer,String> pair = parseFromPath(data.getPath());
                final RuleDesc desc = parseFromData(data.getData());
                if (null != pair && null != desc ) {
                    _rules = _rules.addOrUpdateRule(pair.getFirst(), desc.getScheme() + "://" + pair.getSecond(), 
                            desc.getRegexs());
//                    Arrays.asList( desc.getRegexs() ).toArray(new RoutingInfo[0])
                }
                
                return currentEventHandler();
            }
            
            @OnEvent(event = "NODE_REMOVED")
            private BizStep nodeRemoved(final TreeCacheEvent event) throws Exception {
                final ChildData data = event.getData();
                final Pair<Integer,String> pair = parseFromPath(data.getPath());
                if (null != pair ) {
                    //  TODO add scheme info
                    _rules = _rules.removeRule(pair.getFirst(), pair.getSecond());
//                    Arrays.asList( desc.getRegexs() ).toArray(new RoutingInfo[0])
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
        final String pathToHost = path.substring(_root.length() + ( !_root.endsWith("/") ? 1 : 0 ));
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
