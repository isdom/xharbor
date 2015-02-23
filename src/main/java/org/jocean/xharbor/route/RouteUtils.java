/**
 * 
 */
package org.jocean.xharbor.route;

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
}
