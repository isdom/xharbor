/**
 * 
 */
package org.jocean.xharbor.relay;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;

import java.lang.reflect.Field;

import org.jocean.event.api.EventReceiverSource;
import org.jocean.xharbor.spi.RelayAgent;

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
        final RelayFlow flow = createRelayFlow().attach(channelCtx, httpRequest);
        
        try {
            final Field field = flow.WAIT.getClass().getDeclaredField("this$0");
            final Object outer = field.get(flow.WAIT);
            System.out.print(outer);
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        } catch (SecurityException e) {
            e.printStackTrace();
        } catch (IllegalArgumentException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        
        this._source.create(flow, flow.WAIT);
        
        return flow.queryInterfaceInstance(RelayTask.class);
    }
    
    protected abstract RelayFlow createRelayFlow();
    
    private final EventReceiverSource _source;
}
