/**
 * 
 */
package org.jocean.xharbor.relay;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;

import org.jocean.event.api.EventReceiverSource;
import org.jocean.event.api.EventUtils;
import org.jocean.xharbor.api.RelayAgent;

/**
 * @author isdom
 *
 */
public abstract class RelayAgentImpl implements RelayAgent {
    public RelayAgentImpl(final EventReceiverSource source) {
        this._source = source;
    }
    
    @Override
    public RelayTask createRelayTask(
            final ChannelHandlerContext channelCtx, 
            final HttpRequest httpRequest) {
        return EventUtils.buildInterfaceAdapter(RelayTask.class,  
            this._source.createFromInnerState(
                createRelayFlow().attach(channelCtx, httpRequest).WAIT));
    }
    
    protected abstract RelayFlow createRelayFlow();
    
    private final EventReceiverSource _source;
}
