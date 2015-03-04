/**
 * 
 */
package org.jocean.xharbor.api;

import io.netty.handler.codec.http.HttpRequest;

import java.net.URI;

import org.jocean.http.HttpRequestTransformer;
import org.jocean.httpclient.api.GuideBuilder;

/**
 * @author isdom
 *
 */
public interface Target {
    
    public GuideBuilder getGuideBuilder();
    
    public URI serviceUri();
    
    public String rewritePath(final String path);
    
    public int addWeight(final int deltaWeight);
    
    public void markServiceDownStatus(final boolean isDown);
    
    public void markAPIDownStatus(final boolean isDown);

    public boolean isNeedAuthorization(final HttpRequest httpRequest);
    
    public boolean isCheckResponseStatus();
    
    public boolean isShowInfoLog();
    
    public HttpRequestTransformer getHttpRequestTransformerOf(final HttpRequest httpRequest);
}
