package org.jocean.xharbor.mbean;

import org.jocean.http.server.mbean.InboundIndicator;
import org.jocean.http.util.Nettys.ServerChannelAware;
import org.jocean.idiom.jmx.MBeanRegister;
import org.jocean.idiom.jmx.MBeanRegisterAware;

import io.netty.channel.ServerChannel;

public class RelayInboundIndicator extends InboundIndicator 
    implements ServerChannelAware, MBeanRegisterAware {

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
    
    private MBeanRegister _register;
}
