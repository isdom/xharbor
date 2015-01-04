package org.jocean.httpgateway.biz;

import io.netty.handler.codec.http.HttpRequest;


public interface HttpDispatcher<RELAYCTX> {
    
    public RELAYCTX dispatch(final HttpRequest request);
}
