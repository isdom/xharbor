package org.jocean.xharbor.route;

import java.net.URI;

public interface RoutingRules extends RoutingRulesMXBean {
    public URI[] calculateRoute(final String path);
}
