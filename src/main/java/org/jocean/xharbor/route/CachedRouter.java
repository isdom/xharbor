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
public class CachedRouter<INPUT, OUTPUT> implements Router<INPUT, OUTPUT>, 
    RouterUpdatable<INPUT, OUTPUT> {

    private static final Logger LOG = LoggerFactory
            .getLogger(CachedRouter.class);
    
    public interface OnRouterUpdated<I, O> extends 
        Visitor2<Router<I, O>,Router<I, O>> {
    }
    
    public interface OnRouted<I, O> extends 
        Visitor2<I, O> {
    }
    
    @Override
    public OUTPUT calculateRoute(final INPUT input, final Context routectx) {
        return this._cache.get(input);
    }

    @Override
    public void updateRouter(final Router<INPUT, OUTPUT> routerImpl) {
        this._implUpdater.updateImpl(routerImpl);
    }

    public CachedRouter(final EventReceiverSource source, 
            final OnRouterUpdated<INPUT, OUTPUT> onRouterUpdated, 
            final OnRouted<INPUT, OUTPUT> onRouted) {
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
                try {
                    _onRouterUpdated.visit(prevImpl, newImpl);
                } catch (Exception e) {
                    LOG.warn("exception when call onCacheCleared({}), detail: {}",
                            _onRouterUpdated, ExceptionUtils.exception2detail(e));
                }
                
                return currentEventHandler();
            }
            
            @OnEvent(event = "onRouted")
            private BizStep onRouted(final INPUT input) {
                try {
                    _onRouted.visit(input, _cache.get(input));
                } catch (Exception e) {
                    LOG.warn("exception when call onRouted({}) with ctx({}), detail: {}",
                            _onRouted, input, ExceptionUtils.exception2detail(e));
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
    
    private final OnRouterUpdated<INPUT, OUTPUT> _onRouterUpdated;
    private final OnRouted<INPUT, OUTPUT> _onRouted;
    private final AtomicReference<Router<INPUT, OUTPUT>> _implRef = 
            new AtomicReference<Router<INPUT, OUTPUT>>(null);
    
    private final ImplUpdater<INPUT, OUTPUT> _implUpdater;
    private final SimpleCache<INPUT, OUTPUT> _cache = new SimpleCache<INPUT, OUTPUT>(
            //  ifAbsent
            new Function<INPUT, OUTPUT>() {
                @Override
                public OUTPUT apply(final INPUT input) {
                    return _implRef.get().calculateRoute(input, null);
                }
            },
            //  ifAssociated
            new Visitor2<INPUT, OUTPUT>() {
                @Override
                public void visit(final INPUT input, final OUTPUT output) throws Exception {
                    _implUpdater.onRouted(input);
                }});
}
