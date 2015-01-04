package org.jocean.httpgateway.api;

import io.netty.handler.codec.http.HttpRequest;


public interface HttpDispatcher<RELAYCTX> {
    
    public RELAYCTX dispatch(final HttpRequest request);
}
