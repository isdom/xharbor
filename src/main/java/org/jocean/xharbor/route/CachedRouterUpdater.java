/**
 * 
 */
package org.jocean.xharbor.route;

import org.jocean.idiom.Visitor;
import org.jocean.xharbor.api.Router;

/**
 * @author isdom
 *
 */
public class CachedRouterUpdater<I, O> implements Visitor<Router<I, O>> {
    public CachedRouterUpdater(final CachedRouter<I, O> cachedRouter) {
        this._cachedRouter = cachedRouter;
    }
    
    @Override
    public void visit(final Router<I, O> rules) throws Exception {
        this._cachedRouter.setImpl(rules);
    }
    
    private final CachedRouter<I, O> _cachedRouter;
};
