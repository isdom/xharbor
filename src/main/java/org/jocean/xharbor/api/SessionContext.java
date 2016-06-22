package org.jocean.xharbor.api;

import io.netty.handler.codec.http.HttpObject;
import rx.Observable;

public interface SessionContext {
    public Observable<HttpObject> inbound();
    public Observable<HttpObject> outbound();
}
