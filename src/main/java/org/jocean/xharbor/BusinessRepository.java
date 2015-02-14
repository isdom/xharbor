package org.jocean.xharbor;

import org.jocean.xharbor.api.RelayAgent;

public interface BusinessRepository {
    public RelayAgent getBusinessAgent(final String name);
}
