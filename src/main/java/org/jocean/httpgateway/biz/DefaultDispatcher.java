/**
 * 
 */
package org.jocean.httpgateway.biz;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.httpgateway.route.RoutingRules;
import org.jocean.idiom.Function;
import org.jocean.idiom.Pair;
import org.jocean.idiom.SimpleCache;
import org.jocean.idiom.Visitor2;
import org.jocean.j2se.MBeanRegisterSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class DefaultDispatcher implements HttpRequestDispatcher {
    private static final Logger LOG = LoggerFactory
            .getLogger(DefaultDispatcher.class);

    public DefaultDispatcher() {
    }
    
    public void setRoutingRules(final RoutingRules rules) {
        final RoutingRules prev = this._routingRulesRef.get();
        if ( null != prev ) {
            this._mbeanSupport.unregisterMBean("name=rules");
        }
        this._routingRulesRef.set(rules);
        // clear all cached routing items
        this._router.clear();
        this._mbeanSupport.registerMBean("name=rules", rules);
    }
    
    @Override
    public URI dispatch(final HttpRequest request) {
        final QueryStringDecoder decoder = new QueryStringDecoder(request.getUri());

        final String path = decoder.path();
        if ( LOG.isDebugEnabled()) {
            LOG.debug("dispatch for path:{}", path);
        }
        final URI[] uris = this._router.get(path);
        if (uris != null && uris.length > 0) {
            final URI uri = uris[(int)(Math.random() * uris.length)];
            final DispatchCounter counter = this._counters.get(Pair.of(path, uri));
            counter.inc();
            return uri;
        }
        else {
            return null;
        }
    }

    public void registerAllMBean() {
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
    }
    
    public void destroy() {
        this._mbeanSupport.destroy();
    }
    
    public static interface RouteMXBean {
        public String[] getRoutes();
    }
    
    public static interface DispatchCounterMXBean {
        public int getCount();
    }
    
    public static class DispatchCounter implements DispatchCounterMXBean {
        public int getCount() {
            return this._counter.get();
        }
        
        public void inc() {
            this._counter.incrementAndGet();
        }
        
        private final AtomicInteger _counter = new AtomicInteger(0);
    }
    
    private AtomicReference<RoutingRules> _routingRulesRef = new AtomicReference<RoutingRules>(null);
    
    private final MBeanRegisterSupport _mbeanSupport = 
            new MBeanRegisterSupport("org.jocean:type=gateway,attr=route", null);
    
    private final SimpleCache<String, URI[]> _router = new SimpleCache<String, URI[]>(
            new Function<String, URI[]>() {
        public URI[] apply(final String path) {
            final RoutingRules rules = _routingRulesRef.get();
            final URI[] result = ( null != rules ? rules.calculateRoute(path) : new URI[0] );
            _mbeanSupport.registerMBean("path=" + path,
                    new RouteMXBean() {
                        @Override
                        public String[] getRoutes() {
                            return new ArrayList<String>() {
                                private static final long serialVersionUID = 1L;
                                {
                                    for (URI uri : result) {
                                        this.add(uri.toString());
                                    }
                                }
                            }.toArray(new String[0]);
                        }
                    });
            return result;
        }
    });
    
    private final Function<Pair<String, URI>,DispatchCounter> _counterMaker = 
            new Function<Pair<String, URI>,DispatchCounter>() {
        @Override
        public DispatchCounter apply(final Pair<String, URI> input) {
            return new DispatchCounter();
        }};

    private final Visitor2<Pair<String,URI>,DispatchCounter> _counterRegister = 
            new Visitor2<Pair<String,URI>,DispatchCounter>() {
        @Override
        public void visit(final Pair<String, URI> pair, final DispatchCounter newCounter)
                throws Exception {
            _mbeanSupport.registerMBean("path=" + pair.getFirst() + ",dest=" + pair.getSecond().toString().replaceAll(":", ""), 
                    newCounter);
        }};

    private final SimpleCache<Pair<String, URI>,DispatchCounter> _counters = 
            new SimpleCache<Pair<String, URI>,DispatchCounter>(this._counterMaker, this._counterRegister);
        
}
