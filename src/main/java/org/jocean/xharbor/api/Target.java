/**
 * 
 */
package org.jocean.xharbor.api;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import java.net.URI;

/**
 * @author isdom
 *
 */
public interface Target {
        
    public URI serviceUri();
    
    public void markServiceDownStatus(final boolean isDown);
    
    public int addWeight(final int deltaWeight);
    
    public void markAPIDownStatus(final boolean isDown);
    
//    public void rewriteRequest(final HttpRequest request);
//
//    public boolean isNeedAuthorization(final HttpRequest httpRequest);
//    
//    public FullHttpResponse needShortResponse(final HttpRequest httpRequest);
//    
//    public void rewriteResponse(final HttpResponse response);
//    
//    public boolean isCheckResponseStatus();
}
