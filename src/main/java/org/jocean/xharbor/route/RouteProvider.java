package org.jocean.xharbor.route;

import java.net.URI;

public interface RouteProvider {
    public URI[] calculateRoute(final String path);
}
