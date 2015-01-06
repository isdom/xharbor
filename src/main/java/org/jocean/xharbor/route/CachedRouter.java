/**
 * 
 */
package org.jocean.xharbor.route;

import java.util.concurrent.atomic.AtomicReference;

import org.jocean.event.api.AbstractFlow;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventReceiverSource;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Function;
import org.jocean.idiom.SimpleCache;
import org.jocean.idiom.Visitor2;
import org.jocean.xharbor.spi.Router;
import org.jocean.xharbor.spi.RouterUpdatable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class CachedRouter<ROUTECTX, RELAYCTX> implements Router<ROUTECTX, RELAYCTX>, 
    RouterUpdatable<ROUTECTX, RELAYCTX> {

    private static final Logger LOG = LoggerFactory
            .getLogger(CachedRouter.class);
    
    public interface OnRouterUpdated<ROUTECTX, RELAYCTX> extends 
        Visitor2<Router<ROUTECTX, RELAYCTX>,Router<ROUTECTX, RELAYCTX>> {
    }
    
    public interface OnRouted<ROUTECTX, RELAYCTX> extends 
        Visitor2<ROUTECTX, RELAYCTX> {
    }
    
    @Override
    public RELAYCTX calculateRoute(final ROUTECTX routectx) {
        return this._cache.get(routectx);
    }

    @Override
    public void updateRouter(final Router<ROUTECTX, RELAYCTX> routerImpl) {
        this._implUpdater.updateImpl(routerImpl);
    }

    public CachedRouter(final EventReceiverSource source, 
            final OnRouterUpdated<ROUTECTX, RELAYCTX> onRouterUpdated, 
            final OnRouted<ROUTECTX, RELAYCTX> onRouted) {
//        this._mbeanSupport.registerMBean("name=table", new RouteMXBean() {
//            @Override
//            public String[] getRoutes() {
//                return new ArrayList<String>() {
//                    private static final long serialVersionUID = 1L;
//                {
//                    final Iterator<Map.Entry<String, URI[]>> itr = _cache.snapshot().entrySet().iterator();
//                    while (itr.hasNext()) {
//                        final Map.Entry<String, URI[]> entry = itr.next();
//                        this.add(entry.getKey() + "-->" + Arrays.toString( entry.getValue() ));
//                    }
//                }}.toArray(new String[0]);
//            }});
        
        this._onRouterUpdated = onRouterUpdated;
        this._onRouted = onRouted;
        this._implUpdater = new UpdateImplFlow() {{
                source.create(this, this.UPDATE);
            }}.queryInterfaceInstance(ImplUpdater.class);
    }
    
    private interface ImplUpdater<ROUTECTX, RELAYCTX> {
        public void updateImpl(final Router<ROUTECTX, RELAYCTX> impl);
        public void onRouted(final ROUTECTX routectx);
    }
    
    private class UpdateImplFlow extends AbstractFlow<UpdateImplFlow> {
        final BizStep UPDATE = new BizStep("routing.UPDATE") {

            @OnEvent(event = "updateImpl")
            private BizStep updateImpl(final Router<ROUTECTX, RELAYCTX> newImpl) {
                final Router<ROUTECTX, RELAYCTX> prevImpl = _implRef.getAndSet(newImpl);
                // clear all cached routing
                _cache.clear();
                try {
                    _onRouterUpdated.visit(prevImpl, newImpl);
                } catch (Exception e) {
                    LOG.warn("exception when call onCacheCleared({}), detail: {}",
                            _onRouterUpdated, ExceptionUtils.exception2detail(e));
                }
                
                return currentEventHandler();
            }
            
            @OnEvent(event = "onRouted")
            private BizStep onRouted(final ROUTECTX routectx) {
                try {
                    _onRouted.visit(routectx, _cache.get(routectx));
                } catch (Exception e) {
                    LOG.warn("exception when call onRouted({}) with ctx({}), detail: {}",
                            _onRouted, routectx, ExceptionUtils.exception2detail(e));
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
    
    private final OnRouterUpdated<ROUTECTX, RELAYCTX> _onRouterUpdated;
    private final OnRouted<ROUTECTX, RELAYCTX> _onRouted;
    private final AtomicReference<Router<ROUTECTX, RELAYCTX>> _implRef = 
            new AtomicReference<Router<ROUTECTX, RELAYCTX>>(null);
    
    private final ImplUpdater<ROUTECTX, RELAYCTX> _implUpdater;
    private final SimpleCache<ROUTECTX, RELAYCTX> _cache = new SimpleCache<ROUTECTX, RELAYCTX>(
            //  ifAbsent
            new Function<ROUTECTX, RELAYCTX>() {
                @Override
                public RELAYCTX apply(final ROUTECTX routectx) {
                    return _implRef.get().calculateRoute(routectx);
                }
            },
            //  ifAssociated
            new Visitor2<ROUTECTX, RELAYCTX>() {
                @Override
                public void visit(final ROUTECTX routectx, final RELAYCTX relayctx) throws Exception {
                    _implUpdater.onRouted(routectx);
                }});
}
