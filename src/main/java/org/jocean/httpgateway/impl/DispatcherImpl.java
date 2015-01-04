/**
 * 
 */
package org.jocean.httpgateway.impl;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.httpgateway.biz.HttpDispatcher;
import org.jocean.httpgateway.route.RoutingRules;
import org.jocean.idiom.Function;
import org.jocean.idiom.SimpleCache;
import org.jocean.j2se.MBeanRegisterSupport;
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
    
    public static interface RouteMXBean {
        public String[] getRoutes();
    }
    
    public DispatcherImpl(final MemoFactory memoFactory) {
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
    }
    
    public void setRoutingRules(final RoutingRules rules) {
        final RoutingRules prev = this._routingRulesRef.get();
        if ( null != prev ) {
            this._mbeanSupport.unregisterMBean("name=rules");
        }
        //  TODO check multi-thread safe
        this._routingRulesRef.set(rules);
        // clear all cached routing items
        this._routerMBeanSupport.unregisterAllMBeans();
        this._router.clear();
        this._mbeanSupport.registerMBean("name=rules", rules);
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
            new MBeanRegisterSupport("org.jocean:type=gateway,attr=route", null);
    private final MBeanRegisterSupport _routerMBeanSupport = 
            new MBeanRegisterSupport("org.jocean:type=gateway,attr=route", null);
    
    private final SimpleCache<String, URI[]> _router = new SimpleCache<String, URI[]>(
            new Function<String, URI[]>() {
        public URI[] apply(final String path) {
            final RoutingRules rules = _routingRulesRef.get();
            final URI[] routes = ( null != rules ? rules.calculateRoute(path) : new URI[0] );
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
            return routes;
        }
    });
}
