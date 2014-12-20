/**
 * 
 */
package org.jocean.httpgateway.impl;

import io.netty.channel.ChannelHandlerContext;

import java.net.URI;

import org.jocean.event.api.EventReceiverSource;
import org.jocean.httpclient.HttpStack;
import org.jocean.httpgateway.ProxyAgent;

/**
 * @author isdom
 *
 */
public class ProxyAgentImpl implements ProxyAgent {
    public ProxyAgentImpl(final HttpStack httpStack, final EventReceiverSource source) {
        this._stack = httpStack;
        this._source = source;
    }
    
    @Override
    public ProxyTask createProxyTask(
            final URI uri,
            final ChannelHandlerContext ctx) {
        final ProxyFlow flow = 
                new ProxyFlow(this._stack, uri, ctx);
        
        this._source.create(flow, flow.WAIT);
        
        return flow.queryInterfaceInstance(ProxyTask.class);
    }

    private final HttpStack _stack;
    private final EventReceiverSource _source;
}
