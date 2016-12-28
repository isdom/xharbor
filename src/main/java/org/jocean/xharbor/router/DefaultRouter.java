/**
 * 
 */
package org.jocean.xharbor.router;

import java.util.Iterator;
import java.util.Map;
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
import org.jocean.xharbor.routing.RuleSet;
import org.jocean.xharbor.routing.RuleSet.MatchResult;
import org.jocean.xharbor.util.RulesMBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import rx.Observable;

/**
 * @author isdom
 *
 */
public class DefaultRouter implements Router<RoutingInfo, Dispatcher>, RulesMBean {
    
    private static final Logger LOG = LoggerFactory
            .getLogger(DefaultRouter.class);

    private final DefaultDispatcher EMPTY_DISPATCHER = 
            new DefaultDispatcher(RuleSet.EMPTY_TARGETS, 
                    RuleSet.NOP_REQ_REWRITER, 
                    RuleSet.NOP_RESP_REWRITER, 
                    RuleSet.NOP_AUTHORIZATION, 
                    null,
                    null,
                    null,
                    null,
                    null) {
        
        @Override
        public Observable<HttpObject> response(
                final ResponseCtx ctx,
                final RoutingInfo info,
                final HttpRequest request, 
                final Observable<? extends HttpObject> fullRequest) {
            LOG.warn("can't found matched target service for http inbound ({})\nrequest:[{}]\njust return 200 OK.", 
                    ctx.transport, request);
            _noRoutingMemo.incRoutingInfo(info);
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
    public Map<String, Map<String, Object>> getRoutingRules() {
        return Maps.newHashMap();
//        return new ArrayList<String>() {
//            private static final long serialVersionUID = 1L;
//        {
//            final Iterator<RuleSet> itr = _allruleset.iterator();
//            while (itr.hasNext()) {
//                this.addAll(itr.next().getRules());
//            }
//        }}.toArray(new String[0]);
    }
    
    @Override
    public Dispatcher calculateRoute(final RoutingInfo info, final Context routectx) {
        final Iterator<RuleSet> itr = this._allruleset.iterator();
        while (itr.hasNext()) {
            final RuleSet rules = itr.next();
            final MatchResult result = rules.match(info);
            if (null != result) {
                return new DefaultDispatcher(
                        result._targets, 
                        result._rewriteRequest,
                        result._rewriteResponse,
                        result._authorization, 
                        result._responses,
                        this._serviceMemo,
                        this._httpClient,
                        this._memoBuilder,
                        this._noRoutingMemo);
            }
        }
        return EMPTY_DISPATCHER;
    }

    public void addRules(final RuleSet rules) {
        this._allruleset.add(rules);
    }
    
    public void removeRules(final RuleSet rules) {
        this._allruleset.remove(rules);
    }
    
    private final ServiceMemo _serviceMemo;
    private final RoutingInfoMemo _noRoutingMemo;
    private final RelayMemo.Builder _memoBuilder;
    private final HttpClient _httpClient;
    private final SortedSet<RuleSet> _allruleset = new ConcurrentSkipListSet<RuleSet>();
}
