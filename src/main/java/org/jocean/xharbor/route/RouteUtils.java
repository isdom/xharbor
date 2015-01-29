/**
 * 
 */
package org.jocean.xharbor.route;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

import org.jocean.event.api.EventReceiverSource;
import org.jocean.idiom.Function;
import org.jocean.idiom.Pair;
import org.jocean.idiom.SimpleCache;
import org.jocean.j2se.MBeanRegisterSupport;
import org.jocean.xharbor.api.Router;

/**
 * @author isdom
 *
 */
public class RouteUtils {
//    private static final Logger LOG = LoggerFactory
//            .getLogger(RouteUtils.class);

    @SuppressWarnings("rawtypes")
    public static <I, O> Router<I, O> compositeRouter(
            final Class<O> clsO,
            final Router<I,?> inRouter, 
            final Router ... routers) {
        return new Router<I, O>() {

            @SuppressWarnings("unchecked")
            @Override
            public O calculateRoute(final I input, final Context routectx) {
                Object io = inRouter.calculateRoute(input, routectx);
                for ( Router router : routers ) {
                    io = router.calculateRoute(io, routectx);
                }
                return (O)io;
            }};
    }
    
    public interface RoutesMXBean {
        public String[] getRoutes();
    }
    
    //  TODO replace this to RoutesMXBean
    public interface RouteMXBean {
        public String getRoutes();
    }
    
    public static <INPUT, OUTPUT> CachedRouter<INPUT, OUTPUT> buildCachedRouter(
            final String prefix, 
            final EventReceiverSource source, 
            final Function<Pair<INPUT,OUTPUT>, String> genobjname) {
        final MBeanRegisterSupport routerMbeanSupport = 
                new MBeanRegisterSupport(prefix, null);

        final MBeanRegisterSupport urisMBeanSupport =
                new MBeanRegisterSupport(prefix, null);
      
        return new CachedRouter<INPUT, OUTPUT>(source, 
                new CachedRouter.CacheVisitor<INPUT, OUTPUT>() {
                    @Override
                    public void visit(final SimpleCache<INPUT, OUTPUT> cache)
                            throws Exception {
                        routerMbeanSupport.registerMBean("name=routes", new RoutesMXBean() {
                      @Override
                      public String[] getRoutes() {
                          return new ArrayList<String>() {
                              private static final long serialVersionUID = 1L;
                          {
                              final Iterator<Map.Entry<INPUT, OUTPUT>> itr = cache.snapshot().entrySet().iterator();
                              while (itr.hasNext()) {
                                  final Map.Entry<INPUT, OUTPUT> entry = itr.next();
                                  this.add(entry.getKey() + "-->" + entry.getValue().toString());
                              }
                          }}.toArray(new String[0]);
                      }});
                    }
                },
                new CachedRouter.OnRouterUpdated<INPUT, OUTPUT>() {
                    @Override
                    public void visit(final Router<INPUT, OUTPUT> prevImpl, final Router<INPUT, OUTPUT> newImpl)
                            throws Exception {
                      if ( null != prevImpl ) {
                          routerMbeanSupport.unregisterMBean("name=rules");
                      }
                      routerMbeanSupport.registerMBean("name=rules", newImpl);
                      urisMBeanSupport.unregisterAllMBeans();
                    }}, 
                new CachedRouter.OnRouted<INPUT, OUTPUT>() {
                    @Override
                    public void visit(final INPUT input, final OUTPUT output) throws Exception {
                        final String objname = genobjname.apply(Pair.of(input, output));
                        if (null != objname && !urisMBeanSupport.isRegistered(objname)) {
                            urisMBeanSupport.registerMBean(objname,
                                    new RouteMXBean() {
                                        @Override
                                        public String getRoutes() {
                                            return output.toString();
                                        }
                                    });
                        }
                    }});
    }
}
