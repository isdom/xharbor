/**
 * 
 */
package org.jocean.xharbor.route;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

import org.jocean.idiom.Function;
import org.jocean.idiom.Pair;
import org.jocean.idiom.SimpleCache;
import org.jocean.j2se.MBeanRegister;
import org.jocean.xharbor.MBeanRegisterAware;
import org.jocean.xharbor.api.Router;

/**
 * @author isdom
 *
 */
public class CahcedRouterMBeanSetter<I, O> implements MBeanRegisterAware {

    public interface RoutesMXBean {
        public String[] getRoutes();
    }
    
    //  TODO replace this to RoutesMXBean
    public interface RouteMXBean {
        public String getRoutes();
    }
    
    public CahcedRouterMBeanSetter(
            final CachedRouter<I, O> cachedRouter,
            final Function<Pair<I, O>, String> objectNameMaker) {
        this._cachedRouter = cachedRouter;
        this._objectNameMaker = objectNameMaker;
        this._cachedRouter.setOnRouted(
                new CachedRouter.OnRouted<I, O>() {
                    @Override
                    public void visit(final I input, final O output) throws Exception {
                        final String objname = _objectNameMaker.apply(Pair.of(input, output));
                        if (null != objname && !_register.isRegistered(objname)) {
                            _register.registerMBean(objname,
                                new RouteMXBean() {
                                    @Override
                                    public String getRoutes() {
                                        return output.toString();
                                    }
                                });
                        }
                    }});
            
        this._cachedRouter.setOnRouterUpdated(
            new CachedRouter.OnRouterUpdated<I, O>() {
                @Override
                public void visit(final Router<I, O> prevImpl, final Router<I, O> newImpl)
                        throws Exception {
                  _register.unregisterAllMBeans();
                  registerRoutesMBean();
                  _register.registerMBean("name=rules", newImpl);
                }}); 
    }
    
    @Override
    public void setMBeanRegister(final MBeanRegister register) {
        this._register = register;
    }
    
    private void registerRoutesMBean() {
        this._cachedRouter.setCacheVisitor(
            new CachedRouter.CacheVisitor<I, O>() {
                @Override
                public void visit(final SimpleCache<I, O> cache)
                        throws Exception {
                    _register.registerMBean("name=routes", new RoutesMXBean() {
                  @Override
                  public String[] getRoutes() {
                      return new ArrayList<String>() {
                          private static final long serialVersionUID = 1L;
                      {
                          final Iterator<Map.Entry<I, O>> itr = cache.snapshot().entrySet().iterator();
                          while (itr.hasNext()) {
                              final Map.Entry<I, O> entry = itr.next();
                              this.add(entry.getKey() + "-->" + entry.getValue().toString());
                          }
                      }}.toArray(new String[0]);
                  }});
                }
            });
    }

    private final CachedRouter<I, O> _cachedRouter;
    private final Function<Pair<I, O>, String> _objectNameMaker;
    private MBeanRegister _register;
}
