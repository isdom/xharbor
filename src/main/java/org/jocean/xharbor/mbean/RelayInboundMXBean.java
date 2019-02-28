package org.jocean.xharbor.mbean;

import org.jocean.http.server.mbean.InboundMXBean;

public interface RelayInboundMXBean extends InboundMXBean {
    public String getGroup();
    public int getWeight();
}
