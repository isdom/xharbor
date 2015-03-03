/**
 * 
 */
package org.jocean.xharbor.relay;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;

import org.jocean.event.api.EventEngine;
import org.jocean.event.api.EventUtils;
import org.jocean.httpserver.ServerAgent;

/**
 * @author isdom
 *
 */
public abstract class RelayAgent implements ServerAgent {
    public RelayAgent(final EventEngine engine) {
        this._engine = engine;
    }
    
    @Override
    public ServerTask createServerTask(
            final ChannelHandlerContext channelCtx, 
            final HttpRequest httpRequest) {
        return EventUtils.buildInterfaceAdapter(ServerTask.class,  
            this._engine.createFromInnerState(
                createRelayFlow().attach(channelCtx, httpRequest).INIT));
    }
    
    protected abstract RelayFlow createRelayFlow();
    
    private final EventEngine _engine;
}
