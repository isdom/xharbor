/**
 * 
 */
package org.jocean.xharbor.impl;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.event.api.AbstractFlow;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventReceiverSource;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.idiom.Function;
import org.jocean.idiom.Visitor2;
import org.jocean.idiom.SimpleCache;
import org.jocean.j2se.MBeanRegisterSupport;
import org.jocean.xharbor.api.HttpDispatcher;
import org.jocean.xharbor.route.RoutingRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class DispatcherImpl implements HttpDispatcher<RelayContext> {
    private static final Logger LOG = LoggerFactory
            .getLogger(DispatcherImpl.class);

    public interface MemoFactory {
        public RelayContext.RelayMemo getRelayMemo(final String path, final URI relayTo);
    }
    
    public interface RouteMXBean {
        public String[] getRoutes();
    }
    
    public DispatcherImpl(final EventReceiverSource source, final MemoFactory memoFactory) {
        this._mbeanSupport.registerMBean("name=table", new RouteMXBean() {
            @Override
            public String[] getRoutes() {
                return new ArrayList<String>() {
                    private static final long serialVersionUID = 1L;
                {
                    final Iterator<Map.Entry<String, URI[]>> itr = _router.snapshot().entrySet().iterator();
                    while (itr.hasNext()) {
                        final Map.Entry<String, URI[]> entry = itr.next();
                        this.add(entry.getKey() + "-->" + Arrays.toString( entry.getValue() ));
                    }
                }}.toArray(new String[0]);
            }});
        
        this._memoFactory = memoFactory;
        
        this._routingUpdater = new UpdateRoutingFlow() {{
                source.create(this, this.UPDATE);
            }}.queryInterfaceInstance(RoutingUpdater.class);
    }
    
    private interface RoutingUpdater {
        public void updateRoutingRules(final RoutingRules rules);
        public void registerRouteMXBean(final String path);
    }
    
    private class UpdateRoutingFlow extends AbstractFlow<UpdateRoutingFlow> {
        final BizStep UPDATE = new BizStep("routing.UPDATE") {

            @OnEvent(event = "updateRoutingRules")
            private BizStep updateRoutingRules(final RoutingRules rules) {
                final RoutingRules prev = _routingRulesRef.get();
                if ( null != prev ) {
                    _mbeanSupport.unregisterMBean("name=rules");
                }
                _routingRulesRef.set(rules);
                // clear all cached routing items
                _routerMBeanSupport.unregisterAllMBeans();
                _router.clear();
                _mbeanSupport.registerMBean("name=rules", rules);

                return currentEventHandler();
            }
            
            @OnEvent(event = "registerRouteMXBean")
            private BizStep registerRouteMXBean(final String path) {
                if ( !_routerMBeanSupport.isRegistered("path=" + path) ) {
                    final URI[] routes = _router.get(path);
                    final String[] routesAsStringArray = new ArrayList<String>() {
                        private static final long serialVersionUID = 1L;
                        {
                            for (URI uri : routes) {
                                this.add(uri.toString());
                            }
                        }
                    }.toArray(new String[0]);
                    _routerMBeanSupport.registerMBean("path=" + path,
                            new RouteMXBean() {
                                @Override
                                public String[] getRoutes() {
                                    return routesAsStringArray;
                                }
                            });
                }

                return currentEventHandler();
            }
        }
        .freeze();

        @Override
        public String toString() {
            return "UpdateRoutingFlow";
        }
    }
    
    public void updateRoutingRules(final RoutingRules rules) {
        this._routingUpdater.updateRoutingRules(rules);
    }
    
    @Override
    public RelayContext dispatch(final HttpRequest request) {
        final QueryStringDecoder decoder = new QueryStringDecoder(request.getUri());

        final String path = decoder.path();
        if ( LOG.isDebugEnabled()) {
            LOG.debug("dispatch for path:{}", path);
        }
        final URI[] uris = this._router.get(path);
        if (uris != null && uris.length > 0) {
            final URI uri = uris[(int)(Math.random() * uris.length)];
            final RelayContext.RelayMemo memo = this._memoFactory.getRelayMemo(path, uri);
            return new RelayContext() {

                @Override
                public URI relayTo() {
                    return uri;
                }

                @Override
                public RelayMemo memo() {
                    return memo;
                }};
        }
        else {
            return null;
        }
    }

    public void destroy() {
        this._mbeanSupport.destroy();
        this._routerMBeanSupport.destroy();
        this._router.clear();
    }
    
    private final MemoFactory _memoFactory;
    private final AtomicReference<RoutingRules> _routingRulesRef = 
            new AtomicReference<RoutingRules>(null);
    
    private final MBeanRegisterSupport _mbeanSupport = 
            new MBeanRegisterSupport("org.jocean:type=router", null);
    private final MBeanRegisterSupport _routerMBeanSupport = 
            new MBeanRegisterSupport("org.jocean:type=router", null);
    
    private final RoutingUpdater _routingUpdater;
    private final SimpleCache<String, URI[]> _router = new SimpleCache<String, URI[]>(
            //  ifAbsent
            new Function<String, URI[]>() {
                @Override
                public URI[] apply(final String path) {
                    final RoutingRules rules = _routingRulesRef.get();
                    return ( null != rules ? rules.calculateRoute(path) : new URI[0] );
                }
            },
            //  ifAssociated
            new Visitor2<String, URI[]>() {
                @Override
                public void visit(final String path, final URI[] routes) throws Exception {
                    _routingUpdater.registerRouteMXBean(path);
                }});
}
