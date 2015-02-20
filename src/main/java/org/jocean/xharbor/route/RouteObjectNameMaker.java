package org.jocean.xharbor.route;

import org.jocean.idiom.Function;
import org.jocean.idiom.Pair;
import org.jocean.xharbor.api.Dispatcher;
import org.jocean.xharbor.api.RoutingInfo;

public class RouteObjectNameMaker implements
        Function<Pair<RoutingInfo, Dispatcher>, String> {
    
    private static final String normalizeString(final String input) {
        return input.replaceAll(":", "-");
    }

    @Override
    public String apply(final Pair<RoutingInfo, Dispatcher> input) {
        final Dispatcher dispatcher = input.getSecond();
        if (dispatcher.IsValid()) {
            final RoutingInfo info = input.getFirst();
            return "path=" + normalizeString(info.getPath()) + ",method="
                    + info.getMethod() + ",name=routes";
        } else {
            return null;
        }
    }
}
