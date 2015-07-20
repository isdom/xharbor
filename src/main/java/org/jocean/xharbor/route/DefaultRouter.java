/**
 * 
 */
package org.jocean.xharbor.route;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentSkipListSet;

import org.jocean.http.HttpRequestTransformer;
import org.jocean.xharbor.api.Dispatcher;
import org.jocean.xharbor.api.Router;
import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.api.ServiceMemo;
import org.jocean.xharbor.route.Level.MatchResult;
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
            new TargetSet(Level.EMPTY_URIS, false, false, 
                    Level.NOP_REWRITEPATH, Level.NOP_NEEDAUTHORIZATION, null, null);

    public DefaultRouter(
            final ServiceMemo serviceMemo,
            final HttpRequestTransformer.Builder transformerBuilder
            ) {
        this._serviceMemo = serviceMemo;
        this._transformerBuilder = transformerBuilder;
    }
    
    @Override
    public String[] getRoutingRules() {
        return new ArrayList<String>() {
            private static final long serialVersionUID = 1L;
        {
            final Iterator<Level> itr = _levels.iterator();
            while (itr.hasNext()) {
                this.addAll(itr.next().getRules());
            }
        }}.toArray(new String[0]);
    }
    
    @Override
    public Dispatcher calculateRoute(final RoutingInfo info, final Context routectx) {
        final Iterator<Level> itr = this._levels.iterator();
        while (itr.hasNext()) {
            final Level level = itr.next();
            final MatchResult result = level.match(info);
            if (null != result) {
                return new TargetSet(
                        result._uris, 
                        result._isCheckResponseStatus, 
                        result._isShowInfoLog, 
                        result._rewritePath, 
                        result._needAuthorization, 
                        this._serviceMemo,
                        this._transformerBuilder);
            }
        }
        return EMPTY_TARGETSET;
    }

    public void addLevel(final Level level) {
        this._levels.add(level);
    }
    
    public void removeLevel(final Level level) {
        this._levels.remove(level);
    }
    
    private final ServiceMemo _serviceMemo;
    private final HttpRequestTransformer.Builder _transformerBuilder;
    private final SortedSet<Level> _levels = new ConcurrentSkipListSet<Level>();
}
