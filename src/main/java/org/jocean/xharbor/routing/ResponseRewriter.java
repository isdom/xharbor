package org.jocean.xharbor.routing;

import io.netty.handler.codec.http.HttpResponse;
import rx.functions.Action1;


public interface ResponseRewriter {
    public Action1<HttpResponse> genRewriting(final String path);
}
