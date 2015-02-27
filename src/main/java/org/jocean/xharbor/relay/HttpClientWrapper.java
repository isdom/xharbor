package org.jocean.xharbor.relay;

import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;

import org.jocean.httpclient.api.Guide;
import org.jocean.httpclient.api.Guide.GuideReactor;
import org.jocean.httpclient.api.Guide.Requirement;
import org.jocean.httpclient.api.GuideBuilder;
import org.jocean.httpclient.api.HttpClient;
import org.jocean.httpclient.api.HttpClient.HttpReactor;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.ValidationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpClientWrapper {
    
    private static final Logger LOG = LoggerFactory
            .getLogger(HttpClientWrapper.class);
    
    public void setGuide(final Guide guide) {
        this._guide = guide;
    }
    
    public void setHttpClient(final HttpClient httpClient) {
        this._httpClient = httpClient;
    }
    
    public void detachHttpClient() {
        if (null != this._guide) {
            try {
                this._guide.detach();
            } catch (Throwable e) {
                LOG.warn(
                        "exception when detach httpclient {}, detail:{}",
                        this, ExceptionUtils.exception2detail(e));
            }
            this._guide = null;
        }
    }
    
    public void startObtainHttpClient(
            final GuideBuilder guideBuilder,
            final GuideReactor<Integer> reactor, 
            final Requirement requirement) {
        this._guide = guideBuilder.createHttpClientGuide();
        this._guide.obtainHttpClient( 
            this._guideId.updateIdAndGet(), reactor, requirement);
    }
    
    public void sendHttpRequest(final HttpRequest request, final HttpReactor<Integer> reactor) 
            throws Exception {
        this._httpClient.sendHttpRequest(
                this._httpClientId.updateIdAndGet(),
                request, reactor);
    }
    
    public void sendHttpContent(final HttpContent content) 
            throws Exception {
        this._httpClient.sendHttpContent(content);
    }
    
    public boolean validateGuideId(final int guideId) {
        return this._guideId.isValidId(guideId);
    }
    
    public boolean validateHttpClientId(final int clientId) {
        return this._httpClientId.isValidId(clientId);
    }
    
    @Override
    public String toString() {
        return "HttpClientWrapper [guide=" + _guide + ", httpClient="
                + _httpClient + ", guideId=" + _guideId + ", httpClientId="
                + _httpClientId + "]";
    }

    private Guide _guide = null;
    private HttpClient _httpClient = null;
    private final ValidationId _guideId = new ValidationId();
    private final ValidationId _httpClientId = new ValidationId();
}