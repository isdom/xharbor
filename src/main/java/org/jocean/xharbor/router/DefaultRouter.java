/**
 * 
 */
package org.jocean.xharbor.router;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentSkipListSet;

import org.jocean.http.client.HttpClient;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.rx.RxObservables;
import org.jocean.xharbor.api.Dispatcher;
import org.jocean.xharbor.api.RelayMemo;
import org.jocean.xharbor.api.Router;
import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.api.RoutingInfoMemo;
import org.jocean.xharbor.api.ServiceMemo;
import org.jocean.xharbor.routing.RouteLevel;
import org.jocean.xharbor.routing.RouteLevel.MatchResult;
import org.jocean.xharbor.util.RulesMXBean;
import org.jocean.xharbor.util.TargetSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;

/**
 * @author isdom
 *
 */
public class DefaultRouter implements Router<RoutingInfo, Dispatcher>, RulesMXBean {
    
    private static final Logger LOG = LoggerFactory
            .getLogger(DefaultRouter.class);

    private final TargetSet EMPTY_TARGETSET = 
            new TargetSet(RouteLevel.EMPTY_URIS, 
                    RouteLevel.NOP_REQ_REWRITER, 
                    RouteLevel.NOP_RESP_REWRITER, 
                    RouteLevel.NOP_NEEDAUTHORIZATION, 
                    null,
                    null,
                    null,
                    null,
                    null) {
        
        @Override
        public Observable<HttpObject> response(
                final RoutingInfo info,
                final HttpRequest request, 
                final Observable<HttpObject> fullRequest) {
            LOG.warn("can't found matched target service for request:[{}]\njust return 200 OK.", 
                    request);
            _noRoutingMemo.incRoutingInfo(info);
            //   TODO, mark this status
//            setEndReason("relay.NOROUTING");
            return RxObservables.delaySubscriptionUntilCompleted(
                    RxNettys.response200OK(request.getProtocolVersion()),
                    fullRequest);
        }
    };

    public DefaultRouter(
            final ServiceMemo   serviceMemo, 
            final HttpClient    httpClient,
            final RoutingInfoMemo noRoutingMemo,
            final RelayMemo.Builder memoBuilder
            ) {
        this._serviceMemo = serviceMemo;
        this._noRoutingMemo = noRoutingMemo;
        this._httpClient = httpClient;
        this._memoBuilder = memoBuilder;
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
                        result._rewriteRequest,
                        result._rewriteResponse,
                        result._needAuthorization, 
                        result._shortResponse,
                        this._serviceMemo,
                        this._httpClient,
                        this._memoBuilder,
                        this._noRoutingMemo);
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
    private final RoutingInfoMemo _noRoutingMemo;
    private final RelayMemo.Builder _memoBuilder;
    private final HttpClient _httpClient;
    private final SortedSet<RouteLevel> _levels = new ConcurrentSkipListSet<RouteLevel>();
}
