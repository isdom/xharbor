package org.jocean.xharbor.api;

import rx.Single;

public interface HttpMessageProcessor {
    public Single<? extends HttpMessageTransformer> process(final SwitchContext ctx);
}
