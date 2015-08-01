package org.jocean.xharbor.routing;

import rx.functions.Func1;

public interface PathRewriter {
    public Func1<String, String> genRewriting(final String path);
}
