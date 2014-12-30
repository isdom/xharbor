package org.jocean.httpgateway.route;

import java.net.URI;

public interface RoutingRules extends RoutingRulesMXBean {
    public URI[] calculateRoute(final String path);
}
