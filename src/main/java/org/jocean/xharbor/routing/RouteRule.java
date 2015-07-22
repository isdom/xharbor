package org.jocean.xharbor.routing;

import java.net.URI;

import org.jocean.xharbor.api.RoutingInfo;

public interface RouteRule {
    public URI match(final RoutingInfo info);
}
