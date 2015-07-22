package org.jocean.xharbor.routing;

import io.netty.handler.codec.http.HttpRequest;

import org.jocean.idiom.Function;

public interface PathAuthorizer {
    
    public Function<HttpRequest, Boolean> genNeedAuthorization(final String path);
}
