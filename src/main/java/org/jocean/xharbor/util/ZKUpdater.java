package org.jocean.xharbor.util;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.TreeCache;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.apache.curator.framework.recipes.cache.TreeCacheListener;
import org.jocean.event.api.AbstractFlow;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.EventReceiverSource;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZKUpdater<T> {
    public interface Operator<T> {
        public T createEntity();
        
        public T addOrUpdateToEntity(final T entity, final String root, final TreeCacheEvent event) 
                throws Exception;
        
        public T removeFromEntity(final T entity, final String root, final TreeCacheEvent event) 
                throws Exception;
        
        public T applyEntity(final T entity);
    }
    
    private static final Logger LOG = LoggerFactory
            .getLogger(ZKUpdater.class);

    public ZKUpdater(
            final EventReceiverSource source,
            final CuratorFramework client, 
            final String root, 
            final Operator<T> operator) {
        this._operator = operator;
        this._root = root;
        this._zkCache = TreeCache.newBuilder(client, root).setCacheData(true).build();
        this._receiver = new ZKTreeWatcherFlow() {{
            source.create(this, this.UNINITIALIZED);
        }}.queryInterfaceInstance(EventReceiver.class);
        this._entity = this._operator.createEntity();
    }
    
    public void start() {
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

    /**
     * @param newEntity
     */
    private void safeUpdateEntity(final T newEntity) {
        if (null != newEntity) {
            this._entity = newEntity;
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
                    _operator.addOrUpdateToEntity(_entity, _root, event);
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
                    _operator.removeFromEntity(_entity, _root, event);
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
                safeUpdateEntity(_operator.applyEntity(_entity));
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
                    safeUpdateEntity(
                        _operator.applyEntity(_operator.addOrUpdateToEntity(_entity, _root, event)));
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
                    safeUpdateEntity(
                        _operator.applyEntity(_operator.removeFromEntity(_entity, _root, event)));
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
    
    private final String _root;
    private final TreeCache _zkCache;
    private final Operator<T> _operator;
    private final EventReceiver _receiver;
    private T _entity;
}
