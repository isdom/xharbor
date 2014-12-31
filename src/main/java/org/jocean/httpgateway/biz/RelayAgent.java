/**
 * 
 */
package org.jocean.httpgateway.biz;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;

import org.jocean.httpgateway.biz.HttpDispatcher.RelayContext;
import org.jocean.idiom.Detachable;

/**
 * @author isdom
 *
 */
public interface RelayAgent {
    public interface RelayTask extends Detachable {
        public void sendHttpRequest(final HttpRequest httpRequest);
        
        public void sendHttpContent(final HttpContent httpContent);
    }
    
    public RelayTask createRelayTask(final RelayContext relay, final ChannelHandlerContext ctx);
}
