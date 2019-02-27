package org.jocean.xharbor.relay;

import org.springframework.beans.factory.annotation.Value;

public class RequestIsolation {

    @Value("${path}")
    String _path;

    @Value("${redirect.location}")
    String _location;

    @Value("${max.concurrent}")
    int _maxConcurrent = 10;

    @Value("${timeoutInMs}")
    int _timeoutInMs = 0;

    @Override
    public String toString() {
        return new StringBuilder().append("RequestIsolation [path=").append(_path)
                .append(", maxConcurrent=").append(_maxConcurrent)
                .append(", timeoutInMs=").append(_timeoutInMs)
                .append(", location=").append(_location)
                .append("]").toString();
    }

}
