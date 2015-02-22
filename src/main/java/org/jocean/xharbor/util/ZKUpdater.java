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

public class ZKUpdater<CTX> {
    public interface Operator<CTX> {
        public CTX createContext();
        
        public CTX doAdd(final CTX ctx, final String root, final TreeCacheEvent event) 
                throws Exception;
        
        public CTX doUpdate(final CTX ctx, final String root, final TreeCacheEvent event) 
                throws Exception;
        
        public CTX doRemove(final CTX ctx, final String root, final TreeCacheEvent event) 
                throws Exception;
        
        public CTX applyContext(final CTX ctx);
    }
    
    private static final Logger LOG = LoggerFactory
            .getLogger(ZKUpdater.class);

    public ZKUpdater(
            final EventReceiverSource source,
            final CuratorFramework client, 
            final String root, 
            final Operator<CTX> operator) {
        this._operator = operator;
        this._root = root;
        this._zkCache = TreeCache.newBuilder(client, root).setCacheData(true).build();
        this._receiver = new ZKTreeWatcherFlow() {{
            source.create(this, this.UNINITIALIZED);
        }}.queryInterfaceInstance(EventReceiver.class);
        this._context = this._operator.createContext();
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
    
    public void stop() {
        this._zkCache.close();
    }

    /**
     * @param newCtx
     */
    private void safeUpdateCtx(final CTX newCtx) {
        if (null != newCtx) {
            this._context = newCtx;
        }
    }

    private class ZKTreeWatcherFlow extends AbstractFlow<ZKTreeWatcherFlow> {
        final BizStep UNINITIALIZED = new BizStep("zkupdate.UNINITIALIZED") {

            @OnEvent(event = "NODE_ADDED")
            private BizStep nodeAdded(final TreeCacheEvent event) throws Exception {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("handler ({}) with event ({}), try to add or update operator", 
                            currentEventHandler(), event);
                }
                try {
                    _operator.doAdd(_context, _root, event);
                } catch (Exception e) {
                    LOG.warn("exception when doAdd for event({}), detail:{}",
                            event, ExceptionUtils.exception2detail(e));
                }
                
                return currentEventHandler();
            }
            
            @OnEvent(event = "NODE_REMOVED")
            private BizStep nodeRemoved(final TreeCacheEvent event) throws Exception {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("handler ({}) with event ({}), try to remove operator", 
                            currentEventHandler(), event);
                }
                try {
                    _operator.doRemove(_context, _root, event);
                } catch (Exception e) {
                    LOG.warn("exception when doRemove for event({}), detail:{}",
                            event, ExceptionUtils.exception2detail(e));
                }
                
                return currentEventHandler();
            }
            
            @OnEvent(event = "NODE_UPDATED")
            private BizStep nodeUpdated(final TreeCacheEvent event) throws Exception {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("handler ({}) with event ({}), try to update operator", 
                            currentEventHandler(), event);
                }
                try {
                    _operator.doUpdate(_context, _root, event);
                } catch (Exception e) {
                    LOG.warn("exception when doUpdate for event({}), detail:{}",
                            event, ExceptionUtils.exception2detail(e));
                }
                
                return currentEventHandler();
            }
            
            @OnEvent(event = "INITIALIZED")
            private BizStep initialized(final TreeCacheEvent event) throws Exception {
                safeUpdateCtx(_operator.applyContext(_context));
                return INITIALIZED;
            }
        }
        .freeze();
        
        final BizStep INITIALIZED = new BizStep("zkupdate.INITIALIZED") {

            @OnEvent(event = "NODE_ADDED")
            private BizStep nodeAdded(final TreeCacheEvent event) throws Exception {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("handler ({}) with event ({}), try to add operator", 
                            currentEventHandler(), event);
                }
                try {
                    safeUpdateCtx(
                        _operator.applyContext(_operator.doAdd(_context, _root, event)));
                } catch (Exception e) {
                    LOG.warn("exception when doAdd for event({}), detail:{}",
                            event, ExceptionUtils.exception2detail(e));
                }
                
                return currentEventHandler();
            }
            
            @OnEvent(event = "NODE_REMOVED")
            private BizStep nodeRemoved(final TreeCacheEvent event) throws Exception {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("handler ({}) with event ({}), try to remove operator", 
                            currentEventHandler(), event);
                }
                try {
                    safeUpdateCtx(
                        _operator.applyContext(_operator.doRemove(_context, _root, event)));
                } catch (Exception e) {
                    LOG.warn("exception when doRemove for event({}), detail:{}",
                            event, ExceptionUtils.exception2detail(e));
                }
                
                return currentEventHandler();
            }
            
            @OnEvent(event = "NODE_UPDATED")
            private BizStep nodeUpdated(final TreeCacheEvent event) throws Exception {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("handler ({}) with event ({}), try to update operator", 
                            currentEventHandler(), event);
                }
                try {
                    safeUpdateCtx(
                        _operator.applyContext(_operator.doUpdate(_context, _root, event)));
                } catch (Exception e) {
                    LOG.warn("exception when doUpdate for event({}), detail:{}",
                            event, ExceptionUtils.exception2detail(e));
                }
                
                return currentEventHandler();
            }
        }
        .freeze();
    }
    
    private final String _root;
    private final TreeCache _zkCache;
    private final Operator<CTX> _operator;
    private final EventReceiver _receiver;
    private CTX _context;
}
