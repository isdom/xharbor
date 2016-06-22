package org.jocean.xharbor.api;

import rx.Single;

public interface SessionScheduler {
    public Single<? extends HttpMessageTransformer> schedule(final SessionContext ctx);
}
