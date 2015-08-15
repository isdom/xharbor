package org.jocean.xharbor.routing;

import org.jocean.xharbor.api.RoutingInfo;

import io.netty.handler.codec.http.HttpRequest;
import rx.functions.Func1;

public interface AuthorizationRule {
    
    public Func1<HttpRequest, Boolean> genAuthorization(final RoutingInfo info);
}
