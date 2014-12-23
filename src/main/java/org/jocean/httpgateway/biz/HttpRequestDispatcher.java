package org.jocean.httpgateway.biz;

import io.netty.handler.codec.http.HttpRequest;

import java.net.URI;

public interface HttpRequestDispatcher {
    public URI dispatch(final HttpRequest request);
}
