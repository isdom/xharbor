/**
 * 
 */
package org.jocean.xharbor.relay;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;

import org.jocean.event.api.EventReceiverSource;
import org.jocean.httpclient.api.HttpClientPool;
import org.jocean.xharbor.spi.RelayAgent;
import org.jocean.xharbor.spi.Router;

/**
 * @author isdom
 *
 */
public class RelayAgentImpl implements RelayAgent {
    public RelayAgentImpl(
            final HttpClientPool httpClientPool, 
            final EventReceiverSource source) {
        this._httpClientPool = httpClientPool;
        this._source = source;
    }
    
    @Override
    public RelayTask createRelayTask(
            final ChannelHandlerContext channelCtx, 
            final HttpRequest httpRequest) {
        final RelayFlow flow = 
                new RelayFlow(_router, this._httpClientPool, channelCtx, httpRequest);
        
        this._source.create(flow, flow.WAIT);
        
        return flow.queryInterfaceInstance(RelayTask.class);
    }
    
    public void setRouter(final Router<HttpRequest, RelayContext> router) {
        this._router = router;
    }

    private final HttpClientPool _httpClientPool;
    private final EventReceiverSource _source;
    private Router<HttpRequest, RelayContext> _router;
}
