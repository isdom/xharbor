package org.jocean.xharbor.routing;

import org.jocean.idiom.Function;

public interface PathRewriter {
    public Function<String, String> genRewriting(final String path);
}
