/**
 * 
 */
package org.jocean.httpgateway;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;

import java.net.URI;

import org.jocean.idiom.Detachable;

/**
 * @author isdom
 *
 */
public interface ProxyAgent {
    public Detachable createProxyTask(final URI uri, 
            final FullHttpRequest httpRequest, 
            final ChannelHandlerContext ctx);
}
