package org.jocean.xharbor.mbean;

import org.jocean.http.server.mbean.InboundIndicator;
import org.jocean.http.util.Nettys.ServerChannelAware;
import org.jocean.idiom.jmx.MBeanRegister;
import org.jocean.idiom.jmx.MBeanRegisterAware;
import org.springframework.beans.factory.annotation.Value;

import io.netty.channel.ServerChannel;

public class RelayInboundIndicator extends InboundIndicator implements RelayInboundMXBean, ServerChannelAware, MBeanRegisterAware {

    @Override
    public void setServerChannel(final ServerChannel serverChannel) {
        super.setServerChannel(serverChannel);
        this._register.registerMBean("name=httpin,address=" + this.getBindIp()
                +",port=" + this.getPort(), this);
    }

    @Override
    public void setMBeanRegister(final MBeanRegister register) {
        this._register = register;
    }

    @Override
    public String getGroup() {
        return _group;
    }

    @Override
    public int getWeight() {
        return _weight;
    }

    private MBeanRegister _register;

    @Value("${group}")
    String _group = "none";

    @Value("${weight}")
    int _weight = 0;
}
