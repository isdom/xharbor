package org.jocean.xharbor.routing;

import io.netty.handler.codec.http.HttpRequest;
import rx.functions.Action1;


public interface RequestRewriter {
    public Action1<HttpRequest> genRewriting(final String path);
}
