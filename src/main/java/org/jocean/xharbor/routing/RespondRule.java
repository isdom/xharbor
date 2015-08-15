package org.jocean.xharbor.routing;

import org.jocean.xharbor.api.RoutingInfo;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import rx.functions.Func1;

public interface RespondRule {
    public Func1<HttpRequest, FullHttpResponse> genResponser(final RoutingInfo info);
}
