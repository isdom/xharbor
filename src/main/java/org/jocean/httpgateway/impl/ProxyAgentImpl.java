/**
 * 
 */
package org.jocean.httpgateway.impl;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;

import java.net.URI;

import org.jocean.event.api.EventReceiverSource;
import org.jocean.httpclient.HttpStack;
import org.jocean.httpgateway.ProxyAgent;
import org.jocean.idiom.Detachable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class ProxyAgentImpl implements ProxyAgent {
    private static final Logger LOG = LoggerFactory
            .getLogger(ProxyAgentImpl.class);

    public ProxyAgentImpl(final HttpStack httpStack, final EventReceiverSource source) {
        this._stack = httpStack;
        this._source = source;
    }
    
    @Override
    public Detachable createProxyTask(
            final URI uri,
            final FullHttpRequest httpRequest, 
            final ChannelHandlerContext ctx) {
        final ProxyFlow flow = 
                new ProxyFlow(this._stack);
        try {
            this._source.create(flow, flow.WAIT).acceptEvent("start", uri, httpRequest, ctx);
        } catch (Exception e) {
            LOG.warn("exception when acceptEvent for uri:{}/request:{}/ctx:{} ", uri, httpRequest, ctx);
        }
        
        return flow.queryInterfaceInstance(Detachable.class);
    }

    private final HttpStack _stack;
    private final EventReceiverSource _source;
}
