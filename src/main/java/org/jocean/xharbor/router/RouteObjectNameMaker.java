package org.jocean.xharbor.router;

import org.jocean.xharbor.api.Dispatcher;
import org.jocean.xharbor.api.RoutingInfo;

import rx.functions.Func2;

public class RouteObjectNameMaker implements
        Func2<RoutingInfo, Dispatcher, String> {
    
    private static final String normalizeString(final String input) {
        return input.replaceAll(":", "-");
    }

    @Override
    public String call(final RoutingInfo info, final Dispatcher dispatcher) {
        if (dispatcher.IsValid()) {
            return "path=" + normalizeString(info.getPath()) + ",method="
                    + info.getMethod() + ",name=routes";
        } else {
            return null;
        }
    }
}
