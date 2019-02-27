package org.jocean.xharbor.relay;

import java.util.Map;

public interface TradeRelayMXBean {
    public Map<String, String> getSchedulers();
    public Map<String, String> getIsolations();

    public boolean isTracingEnabled();

    public String[] getReactors();
}
