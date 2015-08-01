package org.jocean.xharbor.routing;

import org.jocean.xharbor.api.RoutingInfo;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import rx.functions.Func1;

public interface Responser {
    public Func1<HttpRequest, FullHttpResponse> genResponseBuilder(final RoutingInfo info);
}
