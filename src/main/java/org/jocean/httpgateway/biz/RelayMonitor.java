/**
 * 
 */
package org.jocean.httpgateway.biz;

import java.net.URI;

/**
 * @author isdom
 *
 */
public interface RelayMonitor {
    public static interface CounterMXBean {
        public int getCount();
    }
    
    public static interface Counter extends CounterMXBean {
        public void inc();
    }
    
    public Counter getCounter(final String path, final URI relayTo);
}
