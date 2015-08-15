package org.jocean.xharbor.routing;

import org.jocean.xharbor.api.RoutingInfo;

import io.netty.handler.codec.http.HttpRequest;
import rx.functions.Action1;


public interface RewriteRequestRule {
    public Action1<HttpRequest> genRewriting(final RoutingInfo info);
}
