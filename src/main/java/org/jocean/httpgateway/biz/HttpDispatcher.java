package org.jocean.httpgateway.biz;

import io.netty.handler.codec.http.HttpRequest;

import java.net.URI;


public interface HttpDispatcher {
    public interface RelayContext {
        public URI relayTo();
        public RelayMonitor.Counter counter();
    }
    
    public RelayContext dispatch(final HttpRequest request);
}
