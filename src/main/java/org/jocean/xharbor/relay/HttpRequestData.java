package org.jocean.xharbor.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayList;
import java.util.List;

public class HttpRequestData {
    
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
    
    private void updateRecvHttpRequestState(final HttpObject httpObject) {
        if ( (httpObject instanceof FullHttpRequest) 
            || (httpObject instanceof LastHttpContent)) {
            this._isRequestFully = true;
        }
    }

    private HttpRequest _httpRequest;
    public final List<HttpContent> _contents = new ArrayList<>();
    private boolean _isRequestFully = false;
}