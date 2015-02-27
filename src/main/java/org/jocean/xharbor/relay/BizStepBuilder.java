/**
 * 
 */
package org.jocean.xharbor.relay;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

import org.jocean.event.api.BizStep;
import org.jocean.event.api.annotation.OnEvent;

/**
 * @author isdom
 *
 */
interface BizStepBuilder {
    public BizStep build(
            final HttpRequestData requestData, 
            final ChannelHandlerContext channelCtx);
    
    public static BizStepBuilder waitforRequestFinishedAndResponse401Unauthorized = new BizStepBuilder() {

        @Override
        public BizStep build(
                final HttpRequestData requestData,
                final ChannelHandlerContext channelCtx) {
            if ( requestData.isRequestFully()) {
                response401Unauthorized(requestData, channelCtx);
                return null;
            }
            else {
                return new RESP_401(requestData, channelCtx);
            }
        }

        final class RESP_401 extends BizStep {
            
            RESP_401(
                final HttpRequestData requestData,
                final ChannelHandlerContext channelCtx) {
                super("RESP_401");
                _requestData = requestData;
                _channelCtx = channelCtx;
            }
            
            @OnEvent(event = "onHttpContent")
            private BizStep consumeHttpContent(final HttpContent httpContent) {
                _requestData.addContent(httpContent);
                if (_requestData.isRequestFully()) {
                    response401Unauthorized(_requestData, _channelCtx);
                    return null;
                }
                else {
                    return BizStep.CURRENT_BIZSTEP;
                }
            }
            
            HttpRequestData     _requestData;
            ChannelHandlerContext _channelCtx;
        };
        
        private void response401Unauthorized(HttpRequestData requestData, ChannelHandlerContext channelCtx) {
            final HttpResponse response = new DefaultFullHttpResponse(
                    requestData.request().getProtocolVersion(), HttpResponseStatus.UNAUTHORIZED);
            HttpHeaders.setHeader(response, HttpHeaders.Names.WWW_AUTHENTICATE, "Basic realm=\"iplusmed\"");
            HttpHeaders.setHeader(response, HttpHeaders.Names.CONTENT_LENGTH, 0);
            final ChannelFuture future = channelCtx.writeAndFlush(response);
            if ( !HttpHeaders.isKeepAlive(requestData.request())) {
                future.addListener(ChannelFutureListener.CLOSE);
            }
        }
    };
        
    public static BizStepBuilder waitforRequestFinishedAndResponse200OK = new BizStepBuilder() {
        @Override
        public BizStep build(
                final HttpRequestData requestData,
                final ChannelHandlerContext channelCtx) {
            if ( requestData.isRequestFully()) {
                responseDefault200OK(requestData, channelCtx);
                return null;
            }
            else {
                return new RESP_200OK(requestData, channelCtx);
            }
        }

        final class RESP_200OK extends BizStep {
            
            RESP_200OK(
                final HttpRequestData requestData,
                final ChannelHandlerContext channelCtx) {
                super("RESP_200OK");
                _requestData = requestData;
                _channelCtx = channelCtx;
            }
            
            @OnEvent(event = "onHttpContent")
            private BizStep consumeHttpContent(final HttpContent httpContent) {
                _requestData.addContent(httpContent);
                if (_requestData.isRequestFully()) {
                    responseDefault200OK(_requestData, _channelCtx);
                    return null;
                }
                else {
                    return BizStep.CURRENT_BIZSTEP;
                }
            }
            
            HttpRequestData     _requestData;
            ChannelHandlerContext _channelCtx;
        };
        
        private void responseDefault200OK(HttpRequestData requestData, ChannelHandlerContext channelCtx) {
            final HttpResponse response = new DefaultFullHttpResponse(
                    requestData.request().getProtocolVersion(), HttpResponseStatus.OK);
            HttpHeaders.setHeader(response, HttpHeaders.Names.CONTENT_LENGTH, 0);
            final ChannelFuture future = channelCtx.writeAndFlush(response);
            if (!HttpHeaders.isKeepAlive(requestData.request())) {
                future.addListener(ChannelFutureListener.CLOSE);
            }
        }            
    };
}
