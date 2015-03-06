/**
 * 
 */
package org.jocean.xharbor.route;

import java.util.concurrent.atomic.AtomicReference;

import org.jocean.event.api.AbstractFlow;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventEngine;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Function;
import org.jocean.idiom.SimpleCache;
import org.jocean.idiom.Visitor;
import org.jocean.idiom.Visitor2;
import org.jocean.xharbor.api.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class CachedRouter<INPUT, OUTPUT> implements Router<INPUT, OUTPUT> {

    private static final Logger LOG = LoggerFactory
            .getLogger(CachedRouter.class);
    
    private static final ThreadLocal<Context> _CTX = new ThreadLocal<Context>();
    
    public interface CacheVisitor<I, O> extends 
        Visitor<SimpleCache<I, O>> {
    }
    
    public interface OnRouterUpdated<I, O> extends 
        Visitor2<Router<I, O>,Router<I, O>> {
    }
    
    public interface OnRouted<I, O> extends 
        Visitor2<I, O> {
    }
    
    @Override
    public OUTPUT calculateRoute(final INPUT input, final Context routectx) {
        _CTX.set(routectx);
        
        try {
            return this._cache.get(input);
        } finally {
            _CTX.remove();
        }
    }

    public void updateRouter(final Router<INPUT, OUTPUT> routerImpl) {
        this._implUpdater.updateImpl(routerImpl);
    }

    public CachedRouter(final EventEngine engine) {
        this._engine = engine;
    }
    
    @SuppressWarnings("unchecked")
    public void start() {
        this._implUpdater = new UpdateImplFlow() {{
            _engine.create(CachedRouter.this.toString() + "'s UpdateImpl", this.UPDATE, this);
        }}.queryInterfaceInstance(ImplUpdater.class);
    }
    
    public void setCacheVisitor(final CacheVisitor<INPUT, OUTPUT> cacheVisitor) {
        if ( null != cacheVisitor ) {
            try {
                cacheVisitor.visit(this._cache);
            } catch (Exception e) {
                LOG.warn("exception when invoke cache visitor({}), detail:{}", 
                        cacheVisitor, ExceptionUtils.exception2detail(e));
            }
        }
    }
    
    public void setOnRouterUpdated(final OnRouterUpdated<INPUT, OUTPUT> onRouterUpdated) {
        this._onRouterUpdated = onRouterUpdated;
    }
    
    public void setOnRouted(final OnRouted<INPUT, OUTPUT> onRouted) {
        this._onRouted = onRouted;
    }
    
    private interface ImplUpdater<I, O> {
        public void updateImpl(final Router<I, O> impl);
        public void onRouted(final I input);
    }
    
    private class UpdateImplFlow extends AbstractFlow<UpdateImplFlow> {
        final BizStep UPDATE = new BizStep("routing.UPDATE") {

            @OnEvent(event = "updateImpl")
            private BizStep updateImpl(final Router<INPUT, OUTPUT> newImpl) {
                final Router<INPUT, OUTPUT> prevImpl = _implRef.getAndSet(newImpl);
                // clear all cached routing
                _cache.clear();
                final OnRouterUpdated<INPUT, OUTPUT> onRouterUpdated = _onRouterUpdated;
                try {
                    if ( null != onRouterUpdated ) {
                        onRouterUpdated.visit(prevImpl, newImpl);
                    }
                } catch (Exception e) {
                    LOG.warn("exception when call onCacheCleared({}), detail: {}",
                            onRouterUpdated, ExceptionUtils.exception2detail(e));
                }
                
                return currentEventHandler();
            }
            
            @OnEvent(event = "onRouted")
            private BizStep onRouted(final INPUT input) {
                final OnRouted<INPUT, OUTPUT> onRouted = _onRouted;
                try {
                    if ( null != onRouted ) {
                        onRouted.visit(input, _cache.get(input));
                    }
                } catch (Exception e) {
                    LOG.warn("exception when call onRouted({}) with ctx({}), detail: {}",
                            onRouted, input, ExceptionUtils.exception2detail(e));
                }

                return currentEventHandler();
            }
        }
        .freeze();

        @Override
        public String toString() {
            return "UpdateImplFlow";
        }
    }
    
    public void destroy() {
        this._cache.clear();
    }
    
    private final EventEngine _engine;
    
    private volatile OnRouted<INPUT, OUTPUT> _onRouted;
    private volatile OnRouterUpdated<INPUT, OUTPUT> _onRouterUpdated;
    
    private final AtomicReference<Router<INPUT, OUTPUT>> _implRef = 
            new AtomicReference<Router<INPUT, OUTPUT>>(null);
    
    private ImplUpdater<INPUT, OUTPUT> _implUpdater;
    private final SimpleCache<INPUT, OUTPUT> _cache = new SimpleCache<INPUT, OUTPUT>(
            //  ifAbsent
            new Function<INPUT, OUTPUT>() {
                @Override
                public OUTPUT apply(final INPUT input) {
                    return _implRef.get().calculateRoute(input, _CTX.get());
                }
            },
            //  ifAssociated
            new Visitor2<INPUT, OUTPUT>() {
                @Override
                public void visit(final INPUT input, final OUTPUT output) throws Exception {
                    _implUpdater.onRouted(input);
                }});
}
