package org.jocean.xharbor.routing;

import io.netty.handler.codec.http.HttpRequest;
import rx.functions.Func1;

public interface PathAuthorizer {
    
    public Func1<HttpRequest, Boolean> genNeedAuthorization(final String path);
}
