/**
 * 
 */
package org.jocean.xharbor.router;

import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Function;
import org.jocean.idiom.SimpleCache;
import org.jocean.idiom.Visitor;
import org.jocean.idiom.Visitor2;
import org.jocean.j2se.jmx.MBeanRegister;
import org.jocean.j2se.jmx.MBeanRegisterAware;
import org.jocean.xharbor.api.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.functions.Action0;
import rx.functions.Action1;

/**
 * @author isdom
 *
 */
public class CachedRouter<INPUT, OUTPUT> implements Router<INPUT, OUTPUT>, MBeanRegisterAware {
    
    public interface CachedMXBean {
        public void reset();
    }
    
    private static final Logger LOG = LoggerFactory
            .getLogger(CachedRouter.class);
    
    private static final ThreadLocal<Context> _CTX = new ThreadLocal<Context>();
    
    public interface CacheVisitor<I, O> extends 
        Visitor<SimpleCache<I, O>> {
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

    public CachedRouter(final Router<INPUT, OUTPUT> impl) {
        this._impl = impl;
    }
    
    public void start() {
        callRouterUpdated(this._onRouterUpdated);
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
    
    public void setOnRouterUpdated(final Action1<Router<INPUT, OUTPUT>> onRouterUpdated) {
        this._onRouterUpdated = onRouterUpdated;
    }
    
    public void setOnRouted(final OnRouted<INPUT, OUTPUT> onRouted) {
        this._onRouted = onRouted;
    }
    
    @Override
    public void setMBeanRegister(final MBeanRegister register) {
        register.registerMBean("name=cachedRouter", new CachedMXBean() {
            @Override
            public void reset() {
                resetAction().call();
            }});
    }
    
    public void destroy() {
        this._cache.clear();
    }
    
    public Action0 resetAction() {
        return new Action0() {
            @Override
            public void call() {
                _cache.clear();
                if (LOG.isDebugEnabled()) {
                    LOG.debug("reset cache({})'s content.", _cache);
                }
                callRouterUpdated(_onRouterUpdated);
            }};
    }
    
    private void callRouterUpdated(
            final Action1<Router<INPUT, OUTPUT>> onRouterUpdated) {
        try {
            if ( null != onRouterUpdated ) {
                onRouterUpdated.call(_impl);
            }
        } catch (Exception e) {
            LOG.warn("exception when call onRouterUpdated({}), detail: {}",
                    onRouterUpdated, ExceptionUtils.exception2detail(e));
        }
    }
    
    private volatile OnRouted<INPUT, OUTPUT> _onRouted;
    private volatile Action1<Router<INPUT, OUTPUT>> _onRouterUpdated;
    
    private final Router<INPUT, OUTPUT> _impl;
    
    private final SimpleCache<INPUT, OUTPUT> _cache = new SimpleCache<INPUT, OUTPUT>(
            //  ifAbsent
            new Function<INPUT, OUTPUT>() {
                @Override
                public OUTPUT apply(final INPUT input) {
                    return _impl.calculateRoute(input, _CTX.get());
                }
            },
            //  ifAssociated
            new Visitor2<INPUT, OUTPUT>() {
                @Override
                public void visit(final INPUT input, final OUTPUT output) throws Exception {
                    final OnRouted<INPUT, OUTPUT> onRouted = _onRouted;
                    try {
                        if ( null != onRouted ) {
                            onRouted.visit(input, output);
                        }
                    } catch (Exception e) {
                        LOG.warn("exception when call onRouted({}) with ctx({}), detail: {}",
                                onRouted, input, ExceptionUtils.exception2detail(e));
                    }
                }});
}
