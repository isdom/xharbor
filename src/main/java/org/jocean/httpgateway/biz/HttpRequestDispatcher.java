package org.jocean.httpgateway.biz;

import io.netty.handler.codec.http.HttpRequest;

import java.net.URI;

import org.jocean.httpgateway.impl.ProxyMonitor;

public interface HttpRequestDispatcher {
    public interface RelayContext {
        public URI relayTo();
        public ProxyMonitor.Counter counter();
    }
    
    public RelayContext dispatch(final HttpRequest request);
}
