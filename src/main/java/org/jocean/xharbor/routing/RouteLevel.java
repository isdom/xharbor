package org.jocean.xharbor.routing;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;

import java.net.URI;
import java.util.Collection;

import org.jocean.xharbor.api.RoutingInfo;

import rx.functions.Func1;

public interface RouteLevel extends Comparable<RouteLevel> {
    
    static final URI[] EMPTY_URIS = new URI[0];
    
    static final Func1<String, String> NOP_REWRITEPATH = new Func1<String, String>() {
        @Override
        public String call(final String input) {
            return input;
        }
        @Override
        public String toString() {
            return "NOP";
        }};
    static final Func1<HttpRequest, Boolean> NOP_NEEDAUTHORIZATION = new Func1<HttpRequest, Boolean>() {
        @Override
        public Boolean call(final HttpRequest request) {
            return false;
        }
        @Override
        public String toString() {
            return "NOP";
        }};
        
    public class MatchResult {
        public final URI[] _uris;
        public final boolean _isCheckResponseStatus;
        public final Func1<String, String> _rewritePath;
        public final Func1<HttpRequest, Boolean> _needAuthorization;
        public final Func1<HttpRequest,FullHttpResponse> _shortResponse;
        
        public MatchResult(final URI[] uris, 
                final boolean isCheckResponseStatus,
                final Func1<String, String> rewritePath,
                final Func1<HttpRequest, Boolean> needAuthorization, 
                final Func1<HttpRequest,FullHttpResponse> shortResponse) {
            this._uris = uris;
            this._isCheckResponseStatus = isCheckResponseStatus;
            this._rewritePath = rewritePath;
            this._needAuthorization = needAuthorization;
            this._shortResponse = shortResponse;
        }
    }
    
    public int getPriority();

    public void addRule(final RouteRule rule);
    
    public void removeRule(final RouteRule rule);
    
    public void addPathRewriter(final PathRewriter rewriter);
    
    public void removePathRewriter(final PathRewriter rewriter);
    
    public void addPathAuthorizer(final PathAuthorizer authorizer);
    
    public void removePathAuthorizer(final PathAuthorizer authorizer);
    
    public void addResponser(final Responser responser);
    
    public void removeResponser(final Responser responser);
    
    public MatchResult match(final RoutingInfo info);

    public Collection<String> getRules();
}
