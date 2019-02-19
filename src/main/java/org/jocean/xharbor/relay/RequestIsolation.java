package org.jocean.xharbor.relay;

import org.springframework.beans.factory.annotation.Value;

public class RequestIsolation {

    @Value("${path}")
    String _path;

    @Value("${redirect.location}")
    String _location;

    @Value("${max.concurrent}")
    int _maxConcurrent = 100;

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("RequestIsolation [path=").append(_path).append(", maxConcurrent=").append(_maxConcurrent)
            .append(", location=").append(_location).append("]");
        return builder.toString();
    }
}
