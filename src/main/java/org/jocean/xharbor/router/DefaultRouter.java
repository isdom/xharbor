/**
 * 
 */
package org.jocean.xharbor.router;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentSkipListSet;

import org.jocean.xharbor.api.Dispatcher;
import org.jocean.xharbor.api.Router;
import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.api.ServiceMemo;
import org.jocean.xharbor.routing.RouteLevel;
import org.jocean.xharbor.routing.RouteLevel.MatchResult;
import org.jocean.xharbor.util.RulesMXBean;
import org.jocean.xharbor.util.TargetSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class DefaultRouter implements Router<RoutingInfo, Dispatcher>, RulesMXBean {
    
    @SuppressWarnings("unused")
    private static final Logger LOG = LoggerFactory
            .getLogger(DefaultRouter.class);

    private static final TargetSet EMPTY_TARGETSET = 
            new TargetSet(RouteLevel.EMPTY_URIS, 
                    false, 
                    RouteLevel.NOP_REQ_REWRITER, 
                    RouteLevel.NOP_NEEDAUTHORIZATION, 
                    null,
                    null);

    public DefaultRouter(final ServiceMemo serviceMemo) {
        this._serviceMemo = serviceMemo;
    }
    
    @Override
    public String[] getRoutingRules() {
        return new ArrayList<String>() {
            private static final long serialVersionUID = 1L;
        {
            final Iterator<RouteLevel> itr = _levels.iterator();
            while (itr.hasNext()) {
                this.addAll(itr.next().getRules());
            }
        }}.toArray(new String[0]);
    }
    
    @Override
    public Dispatcher calculateRoute(final RoutingInfo info, final Context routectx) {
        final Iterator<RouteLevel> itr = this._levels.iterator();
        while (itr.hasNext()) {
            final RouteLevel level = itr.next();
            final MatchResult result = level.match(info);
            if (null != result) {
                return new TargetSet(
                        result._uris, 
                        result._isCheckResponseStatus, 
                        result._rewriteRequest,
                        result._needAuthorization, 
                        result._shortResponse,
                        this._serviceMemo);
            }
        }
        return EMPTY_TARGETSET;
    }

    public void addLevel(final RouteLevel level) {
        this._levels.add(level);
    }
    
    public void removeLevel(final RouteLevel level) {
        this._levels.remove(level);
    }
    
    private final ServiceMemo _serviceMemo;
    private final SortedSet<RouteLevel> _levels = new ConcurrentSkipListSet<RouteLevel>();
}
