/**
 * 
 */
package org.jocean.httpgateway.biz;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;

import org.jocean.idiom.Detachable;

/**
 * @author isdom
 *
 */
public interface RelayAgent<RELAYCTX> {
    public interface RelayTask extends Detachable {
        public void sendHttpRequest(final HttpRequest httpRequest);
        
        public void sendHttpContent(final HttpContent httpContent);
    }
    
    public RelayTask createRelayTask(final RELAYCTX relayCtx, final ChannelHandlerContext channelCtx);
}
