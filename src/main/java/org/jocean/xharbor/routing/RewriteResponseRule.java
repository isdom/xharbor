package org.jocean.xharbor.routing;

import org.jocean.xharbor.api.RoutingInfo;

import io.netty.handler.codec.http.HttpResponse;
import rx.functions.Action1;


public interface RewriteResponseRule {
    public Action1<HttpResponse> genRewriting(final RoutingInfo info);
}
