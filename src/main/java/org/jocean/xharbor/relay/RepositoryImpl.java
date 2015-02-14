/**
 * 
 */
package org.jocean.xharbor.relay;

import io.netty.handler.codec.http.HttpRequest;

import org.apache.curator.framework.CuratorFramework;
import org.jocean.event.api.EventReceiverSource;
import org.jocean.httpclient.api.GuideBuilder;
import org.jocean.idiom.Function;
import org.jocean.idiom.Pair;
import org.jocean.idiom.ProxyBuilder;
import org.jocean.idiom.SimpleCache;
import org.jocean.idiom.Visitor;
import org.jocean.idiom.Visitor2;
import org.jocean.xharbor.BusinessRepository;
import org.jocean.xharbor.api.Dispatcher;
import org.jocean.xharbor.api.RelayAgent;
import org.jocean.xharbor.api.RelayMemo;
import org.jocean.xharbor.api.Router;
import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.api.RoutingInfoMemo;
import org.jocean.xharbor.api.ServiceMemo;
import org.jocean.xharbor.route.CachedRouter;
import org.jocean.xharbor.route.Request2RoutingInfo;
import org.jocean.xharbor.route.RouteUtils;
import org.jocean.xharbor.route.RoutingInfo2Dispatcher;
import org.jocean.xharbor.spi.HttpRequestTransformer;
import org.jocean.xharbor.util.RouteRulesOperator;
import org.jocean.xharbor.util.ZKUpdater;

/**
 * @author isdom
 *
 */
public class RepositoryImpl implements BusinessRepository{

    public RepositoryImpl(
            final EventReceiverSource source,
            final CuratorFramework  zkClient,
            final RelayMemo.Builder memoBuilder,
            final ServiceMemo       serviceMemo, 
            final RoutingInfoMemo   noRoutingMemo,
            final GuideBuilder      guideBuilder,
            final boolean           checkResponseStatus,
            final boolean           showInfoLog,
            final HttpRequestTransformer.Builder transformerBuilder
            ) {
        this._source = source;
        this._zkClient = zkClient;
        this._memoBuilder = memoBuilder;
        this._serviceMemo = serviceMemo;
        this._noRoutingMemo = noRoutingMemo;
        this._guideBuilder = guideBuilder;
        this._checkResponseStatus = checkResponseStatus;
        this._showInfoLog = showInfoLog;
        this._transformerBuilder = transformerBuilder;
    }
    
    @Override
    public RelayAgent getBusinessAgent(final String zkPath) {
        return this._repo.get(zkPath).buildProxy();
    }

    private static final String normalizeString(final String input) {
        return input.replaceAll(":", "-");
    }
    
    private CachedRouter<RoutingInfo, Dispatcher> createRouter(final String zkPath) {
        return RouteUtils.buildCachedRouter("org.jocean:type=router,config="+zkPath, 
                this._source, 
                new Function<Pair<RoutingInfo,Dispatcher>,String>() {
            @Override
            public String apply(final Pair<RoutingInfo,Dispatcher> input) {
                final Dispatcher dispatcher = input.getSecond();
                if (dispatcher.IsValid()) {
                    final RoutingInfo info = input.getFirst();
                    return "path=" + normalizeString(info.getPath())
                            +",method=" + info.getMethod()
                            +",name=routes";
                }
                else {
                    return null;
                }
            }});
    }
            
    private final EventReceiverSource _source;
    private final CuratorFramework  _zkClient;
    private final RelayMemo.Builder _memoBuilder;
    private final ServiceMemo       _serviceMemo; 
    private final RoutingInfoMemo   _noRoutingMemo;
    private final GuideBuilder      _guideBuilder;
    private final boolean           _checkResponseStatus;
    private final boolean           _showInfoLog;
    private final HttpRequestTransformer.Builder _transformerBuilder;
    private final Function<String, ProxyBuilder<RelayAgent>> _maker = 
            new Function<String, ProxyBuilder<RelayAgent>>() {
        @Override
        public ProxyBuilder<RelayAgent> apply(final String zkPath) {
            return new ProxyBuilder<RelayAgent>(RelayAgent.class);
        }};
                
    private final Visitor2<String, ProxyBuilder<RelayAgent>> _register = 
            new Visitor2<String, ProxyBuilder<RelayAgent>>() {
        @Override
        public void visit(final String zkPath,final ProxyBuilder<RelayAgent> proxyBuilder) 
                throws Exception {
            final CachedRouter<RoutingInfo, Dispatcher> cachedRouter = createRouter(zkPath);
            final Router<HttpRequest, Dispatcher> router = 
                    RouteUtils.compositeRouter(Dispatcher.class, new Request2RoutingInfo(), cachedRouter);
            proxyBuilder.setImpl( new RelayAgentImpl(_source) {
                @Override
                protected RelayFlow createRelayFlow() {
                    return new RelayFlow(
                            router,
                            _memoBuilder,
                            _serviceMemo, 
                            _noRoutingMemo,
                            _guideBuilder,
                            _checkResponseStatus,
                            _showInfoLog,
                            _transformerBuilder
                            );
                }});
            final Visitor<RoutingInfo2Dispatcher> visitor = new Visitor<RoutingInfo2Dispatcher>() {
                @Override
                public void visit(final RoutingInfo2Dispatcher rules) throws Exception {
                    cachedRouter.updateRouter(rules);
                }};
            final RouteRulesOperator operator = new RouteRulesOperator(visitor);
            final ZKUpdater<RoutingInfo2Dispatcher> updater = 
                    new ZKUpdater<RoutingInfo2Dispatcher>(_source, _zkClient, zkPath, operator);
            updater.start();
        }};
    
    private final SimpleCache<String, ProxyBuilder<RelayAgent>> _repo = 
            new SimpleCache<String, ProxyBuilder<RelayAgent>>(
                    this._maker, this._register);
}
