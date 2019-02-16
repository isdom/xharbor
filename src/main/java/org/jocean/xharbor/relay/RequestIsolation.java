package org.jocean.xharbor.relay;

import org.springframework.beans.factory.annotation.Value;

public class RequestIsolation {

    @Value("${path}")
    String _path;

    @Value("${redirect.location}")
    String _location;

    @Value("${max.concurrent}")
    int _maxConcurrent = 100;
}
