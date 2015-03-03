package org.jocean.xharbor.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayList;
import java.util.List;

import org.jocean.event.api.BizStep;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.event.api.internal.DefaultInvoker;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Visitor;
import org.jocean.xharbor.spi.HttpRequestTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpRequestWrapper {
    
    private static final Logger LOG = LoggerFactory
            .getLogger(HttpRequestWrapper.class);
    
    @Override
    public String toString() {
        return "HttpRequestData [req=" + _httpRequest + ", contents.count="
                + _contents.size() + ", isRequestFully="
                + _isRequestFully + "]";
    }

    public HttpRequest request() {
        return this._httpRequest;
    }
    
    public ByteBuf retainFullContent() {
        if (this._httpRequest instanceof FullHttpRequest) {
            return ((FullHttpRequest)this._httpRequest).content().retain();
        }
        else {
            //  TODO check wrappedBuffer's retain & release policy
            final ByteBuf[] bufs = new ByteBuf[this._contents.size()];
            for (int idx = 0; idx<this._contents.size(); idx++) {
                bufs[idx] = this._contents.get(idx).content().retain();
            }
            return Unpooled.wrappedBuffer(bufs);
        }
    }
    
    public void setHttpRequest(final HttpRequest request) {
        this._httpRequest = ReferenceCountUtil.retain(request);
        
        updateRecvHttpRequestState(request);
    }
    
    public void addContent(final HttpContent content) {
        this._contents.add(ReferenceCountUtil.retain(content));
        
        updateRecvHttpRequestState(content);
    }
    
    public void foreachContent(final Visitor<HttpContent> visitor) {
        for ( HttpContent content : this._contents) {
            try {
                visitor.visit(content);
            } catch (Exception e) {
                LOG.warn("exception when invoke {}'s visit for content {}, detail: {}", 
                    visitor, content,
                    ExceptionUtils.exception2detail(e));
            }
        }
    }
    
    public void clear() {
        ReferenceCountUtil.safeRelease(this._httpRequest);
        this._httpRequest = null;
        
        for (HttpContent content : this._contents) {
            ReferenceCountUtil.safeRelease(content);
        }
        this._contents.clear();
    }

    public boolean isRequestFully() {
        return this._isRequestFully;
    }
    
	final class RecvContentFully extends BizStep {
		RecvContentFully(
			final String name,
			final Visitor<HttpContent> whenRecvContent,
			final Runnable whenRecvFully,
			final BizStep nextStep) {
			super(name);
			this._whenRecvContent = whenRecvContent;
			this._whenRecvFully = whenRecvFully;
			this._nextStep = nextStep;
		}
		
        @OnEvent(event = "onHttpContent")
        private BizStep recvHttpContentAndTransformRequest(final HttpContent httpContent) {
            addContent(httpContent);
        	if (null!=this._whenRecvContent) {
        		try {
        			this._whenRecvContent.visit(httpContent);
        		}
        		catch (Exception e) {
        			LOG.warn("exception when invoke whenRecvContent:{}, detail: {}",
        					this._whenRecvContent, ExceptionUtils.exception2detail(e));
        		}
        	}
            if (isRequestFully()) {
            	if (null != this._whenRecvFully) {
            		try {
            			this._whenRecvFully.run();
            		}
            		catch (Exception e) {
            			LOG.warn("exception when invoke whenRecvFully {}, detail: {}", 
            					this._whenRecvFully, ExceptionUtils.exception2detail(e));
            		}
            	}
        		return this._nextStep;
            }
            else {
                return BizStep.CURRENT_BIZSTEP;
            }
        }
        
		private final Visitor<HttpContent> _whenRecvContent;
        private final Runnable 	_whenRecvFully;
        private final BizStep	_nextStep;
    }
	
	public BizStep recvFullContentThenGoto(
			final String recvContentName, 
			final Visitor<HttpContent> whenRecvContent,
			final Runnable whenRecvFully,
			final BizStep nextStep,
			final Object... handlers) {
        if (this.isRequestFully()) {
        	if (null != whenRecvFully) {
        		try {
        			whenRecvFully.run();
        		}
        		catch (Exception e) {
        			LOG.warn("exception when invoke whenRecvFully {}, detail: {}", 
        					whenRecvFully, ExceptionUtils.exception2detail(e));
        		}
        	}
    		return nextStep;
        }
        else {
            final BizStep recvContent = new RecvContentFully(recvContentName, whenRecvContent, whenRecvFully, nextStep);
            for (Object handler : handlers) {
            	recvContent.handler(DefaultInvoker.invokers(handler, OnEvent.class));
            }
            return recvContent.freeze();
        }
	}
	
	public void response401Unauthorized(final String vlaueOfWWWAuthenticate, final ChannelHandlerContext channelCtx) {
        final HttpResponse response = new DefaultFullHttpResponse(
        		_httpRequest.getProtocolVersion(), HttpResponseStatus.UNAUTHORIZED);
        HttpHeaders.setHeader(response, HttpHeaders.Names.WWW_AUTHENTICATE, vlaueOfWWWAuthenticate);
        HttpHeaders.setHeader(response, HttpHeaders.Names.CONTENT_LENGTH, 0);
        final ChannelFuture future = channelCtx.writeAndFlush(response);
        if ( !HttpHeaders.isKeepAlive(_httpRequest)) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
	}
	
	public void response200OK(final ChannelHandlerContext channelCtx) {
	    final HttpResponse response = new DefaultFullHttpResponse(
	    		_httpRequest.getProtocolVersion(), HttpResponseStatus.OK);
	    HttpHeaders.setHeader(response, HttpHeaders.Names.CONTENT_LENGTH, 0);
	    final ChannelFuture future = channelCtx.writeAndFlush(response);
	    if (!HttpHeaders.isKeepAlive(_httpRequest)) {
	        future.addListener(ChannelFutureListener.CLOSE);
	    }
	}

	public boolean transformAndReplace(final HttpRequestTransformer requestTransformer) {
        final FullHttpRequest newRequest = transformToFullHttpRequest(requestTransformer);
        if (null!=newRequest) {
            try {
                clear();
                setHttpRequest(newRequest);
                return true;
            } finally {
                newRequest.release();
            }
        }
        else {
            return false;
        }
	}
    
    private FullHttpRequest transformToFullHttpRequest(final HttpRequestTransformer requestTransformer) {
        final ByteBuf content = retainFullContent();
        try {
            return requestTransformer.transform(this._httpRequest, content);
        } finally {
            content.release();
        }
    }
	
    private void updateRecvHttpRequestState(final HttpObject httpObject) {
        if ( (httpObject instanceof FullHttpRequest) 
            || (httpObject instanceof LastHttpContent)) {
            this._isRequestFully = true;
        }
    }

    private HttpRequest _httpRequest;
    private final List<HttpContent> _contents = new ArrayList<>();
    private boolean _isRequestFully = false;
}