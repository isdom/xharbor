package org.jocean.xharbor.transform;

import java.io.UnsupportedEncodingException;
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

public class AUPZKUpdater {
    private static final Logger LOG = LoggerFactory
            .getLogger(AUPZKUpdater.class);

    public AUPZKUpdater(
            final EventReceiverSource source,
            final CuratorFramework client, 
            final String root, 
            final Visitor<CompositeAUPBuilder> updateAPUBuilder) {
        this._updateAPUBuilder = updateAPUBuilder;
        this._root = root;
        this._zkCache = TreeCache.newBuilder(client, root).setCacheData(true).build();
        this._builder = new CompositeAUPBuilder();
        this._source = source;
    }
    
    public void start() {
        this._receiver = new ZKTreeWatcherFlow() {{
            _source.create(this, this.UNINITIALIZED);
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
        final BizStep UNINITIALIZED = new BizStep("updateaup.UNINITIALIZED") {

            @OnEvent(event = "NODE_ADDED")
            private BizStep nodeAdded(final TreeCacheEvent event) throws Exception {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("handler ({}) with event ({}), try to add or update aup", 
                            currentEventHandler(), event);
                }
                try {
                    addOrUpdateToBuilder(_builder, event);
                } catch (Exception e) {
                    LOG.warn("exception when addOrUpdateToBuilder for event({}), detail:{}",
                            event, ExceptionUtils.exception2detail(e));
                }
                
                return currentEventHandler();
            }
            
            @OnEvent(event = "NODE_REMOVED")
            private BizStep nodeRemoved(final TreeCacheEvent event) throws Exception {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("handler ({}) with event ({}), try to remove aup", 
                            currentEventHandler(), event);
                }
                try {
                    removeFromBuilder(_builder, event);
                } catch (Exception e) {
                    LOG.warn("exception when removeFromBuilder for event({}), detail:{}",
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
                updateBuilder(_builder);
                return INITIALIZED;
            }
        }
        .freeze();
        
        final BizStep INITIALIZED = new BizStep("updateaup.INITIALIZED") {

            @OnEvent(event = "NODE_ADDED")
            private BizStep nodeAdded(final TreeCacheEvent event) throws Exception {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("handler ({}) with event ({}), try to add or update rule", 
                            currentEventHandler(), event);
                }
                try {
                    updateBuilder(addOrUpdateToBuilder(_builder, event));
                } catch (Exception e) {
                    LOG.warn("exception when addOrUpdateToBuilder for event({}), detail:{}",
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
                    updateBuilder(removeFromBuilder(_builder, event));
                } catch (Exception e) {
                    LOG.warn("exception when removeFromBuilder for event({}), detail:{}",
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
    
    private CompositeAUPBuilder addOrUpdateToBuilder(
            final CompositeAUPBuilder builder, 
            final TreeCacheEvent event) throws Exception {
        final ChildData data = event.getData();
        final Pair<String,AUPDesc> pair = parseFromPath(data.getPath(), data.getData());
        if (null != pair) {
            final String path = pair.getFirst();
            final AUPDesc desc = pair.getSecond();
            if ( LOG.isDebugEnabled()) {
                LOG.debug("add or update builder with {}/{}",  path, desc);
            }
            return builder.addOrUpdateAUP(path, desc._noapply, desc._applyKeys);
        }
        return null;
    }
    
    private CompositeAUPBuilder removeFromBuilder(
            final CompositeAUPBuilder builder, 
            final TreeCacheEvent event) throws Exception {
        final ChildData data = event.getData();
        final Pair<String,AUPDesc> pair = parseFromPath(data.getPath(), null);
        if (null != pair ) {
            final String path = pair.getFirst();
            if ( LOG.isDebugEnabled()) {
                LOG.debug("remove builder with {}", path);
            }
            return builder.removeAUP(path);
        }
        return null;
    }
    
    private void updateBuilder(final CompositeAUPBuilder newBuilder) {
        if ( null != newBuilder ) {
            this._builder = newBuilder;
            try {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("try to update builder for ({})", newBuilder);
                }
                this._updateAPUBuilder.visit(this._builder.freeze());
            } catch (Exception e) {
                LOG.warn("exception when update builder {} via ({}), detail:{}",
                        newBuilder, this._updateAPUBuilder, ExceptionUtils.exception2detail(e));
            }
        }
    }
    
    public static class AUPDesc {
        
        @Override
        public String toString() {
            return "AUPDesc [_noapply=" + _noapply + ", _applyKeys="
                    + Arrays.toString(_applyKeys) + "]";
        }

        private String _noapply;
        private String[] _applyKeys;
        
        @JSONField(name="noapply")
        public String getNoapply() {
            return _noapply;
        }

        @JSONField(name="noapply")
        public void setNoapply(final String noapply) {
            this._noapply = noapply;
        }

        @JSONField(name="applykeys")
        public String[] getApplyKeys() {
            return this._applyKeys;
        }
        
        @JSONField(name="applykeys")
        public void setApplyKeys(String[] keys) {
            this._applyKeys = keys;
        }
    }
    
    private Pair<String,AUPDesc> parseFromPath(final String path, final byte[] data) {
        if (path.length() <= this._root.length()) {
            return null;
        }
        final String uriPath = path.substring(_root.length() + ( !this._root.endsWith("/") ? 1 : 0 ));
        final String replaced = uriPath.replaceAll("\\|","/");
        
        try {
            return Pair.of(replaced, parseAUPDesc(data));
        }
        catch (Exception e) {
            LOG.warn("exception when parse from path {}, detail:{}", 
                    path, ExceptionUtils.exception2detail(e));
        }
        return null;
    }

    /**
     * @param data
     * @return
     * @throws UnsupportedEncodingException
     */
    private AUPDesc parseAUPDesc(final byte[] data)
            throws UnsupportedEncodingException {
        if (null == data || data.length == 0) {
            return null;
        }
        return JSON.parseObject(new String(data,  "UTF-8"), AUPDesc.class);
    }

    private final String _root;
    private final TreeCache _zkCache;
    private final Visitor<CompositeAUPBuilder> _updateAPUBuilder;
    private CompositeAUPBuilder _builder;
    private EventReceiver _receiver;
    private final EventReceiverSource _source;
}
