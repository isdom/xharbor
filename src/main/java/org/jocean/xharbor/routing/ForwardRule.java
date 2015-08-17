package org.jocean.xharbor.routing;

import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.api.Target;

public interface ForwardRule {
    public Target match(final RoutingInfo info);
}
