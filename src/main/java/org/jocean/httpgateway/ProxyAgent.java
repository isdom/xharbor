/**
 * 
 */
package org.jocean.httpgateway;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;

import org.jocean.httpgateway.biz.HttpRequestDispatcher.RelayContext;
import org.jocean.idiom.Detachable;

/**
 * @author isdom
 *
 */
public interface ProxyAgent {
    public interface ProxyTask extends Detachable {
        public void sendHttpRequest(final HttpRequest httpRequest);
        
        public void sendHttpContent(final HttpContent httpContent);
    }
    
    public ProxyTask createProxyTask(final RelayContext relay, final ChannelHandlerContext ctx);
}
