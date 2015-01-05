package org.jocean.xharbor.relay;

import io.netty.handler.codec.http.HttpRequest;


public interface HttpDispatcher<RELAYCTX> {
    
    public RELAYCTX dispatch(final HttpRequest request);
}
