/**
 * 
 */
package org.jocean.httpgateway.impl;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.httpgateway.biz.RelayMonitor;
import org.jocean.idiom.Function;
import org.jocean.idiom.Pair;
import org.jocean.idiom.SimpleCache;
import org.jocean.idiom.Visitor2;
import org.jocean.j2se.MBeanRegisterSupport;

/**
 * @author isdom
 *
 */
public class RelayMonitorImpl implements RelayMonitor {

    @Override
    public Counter getCounter(final String path, final URI relayTo) {
        return this._counters.get(Pair.of(path, relayTo));
    }

    private static class CounterImpl implements Counter {
        public int getCount() {
            return this._counter.get();
        }
        
        public void inc() {
            this._counter.incrementAndGet();
        }
        
        private final AtomicInteger _counter = new AtomicInteger(0);
    }
    
    private final Function<Pair<String, URI>, Counter> _counterMaker = 
            new Function<Pair<String, URI>, Counter>() {
        @Override
        public Counter apply(final Pair<String, URI> input) {
            return new CounterImpl();
        }};

    private final Visitor2<Pair<String,URI>, Counter> _counterRegister = 
            new Visitor2<Pair<String,URI>, Counter>() {
        @Override
        public void visit(final Pair<String, URI> pair, final Counter newCounter)
                throws Exception {
            _mbeanSupport.registerMBean("path=" + pair.getFirst() + ",dest=" + pair.getSecond().toString().replaceAll(":", ""), 
                    newCounter);
        }};

    private final SimpleCache<Pair<String, URI>, Counter> _counters = 
            new SimpleCache<Pair<String, URI>, Counter>(this._counterMaker, this._counterRegister);
        
    private final MBeanRegisterSupport _mbeanSupport = 
            new MBeanRegisterSupport("org.jocean:type=gateway,attr=route", null);
}
