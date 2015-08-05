/**
 * 
 */
package org.jocean.xharbor.router;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.jocean.idiom.Function;
import org.jocean.idiom.Pair;
import org.jocean.idiom.SimpleCache;
import org.jocean.j2se.jmx.MBeanRegister;
import org.jocean.j2se.jmx.MBeanRegisterAware;
import org.jocean.xharbor.api.Router;

import rx.functions.Action1;

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
                        if (null!=_register) {
                            final String objname = _objectNameMaker.apply(Pair.of(input, output));
                            if (null != objname && !_register.isRegistered(objname)) {
                                _suffixs.add(objname);
                                _register.registerMBean(objname,
                                    new RouteMXBean() {
                                        @Override
                                        public String getRoutes() {
                                            return output.toString();
                                        }
                                    });
                            }
                        }
                    }});
            
        this._cachedRouter.setOnRouterUpdated(
            new Action1<Router<I, O>>() {
                @Override
                public void call(final Router<I, O> impl) {
                  if (null!=_register) {
                      unregisterAllMBean();
                      registerRoutesMBean();
                      _register.registerMBean("name=rules", impl);
                  }
                }}); 
    }
    
    private void unregisterAllMBean() {
        this._register.unregisterMBean("name=routes");
        this._register.unregisterMBean("name=rules");
        for (String suffix : _suffixs) {
            this._register.unregisterMBean(suffix);
        }
        _suffixs.clear();
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
    private final Queue<String> _suffixs = new ConcurrentLinkedQueue<>();
}
