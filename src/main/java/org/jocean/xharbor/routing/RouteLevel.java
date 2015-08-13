package org.jocean.xharbor.routing;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import java.net.URI;
import java.util.Collection;

import org.jocean.xharbor.api.RoutingInfo;

import rx.functions.Action1;
import rx.functions.Func1;

public interface RouteLevel extends Comparable<RouteLevel> {
    
    static final URI[] EMPTY_URIS = new URI[0];
    
    static final Action1<HttpRequest> NOP_REQ_REWRITER = new Action1<HttpRequest>() {
        @Override
        public void call(final HttpRequest request) {
        }
        @Override
        public String toString() {
            return "NOP Request Rewriter";
        }};
    static final Action1<HttpResponse> NOP_RESP_REWRITER = new Action1<HttpResponse>() {
        @Override
        public void call(final HttpResponse response) {
        }
        @Override
        public String toString() {
            return "NOP Response Rewriter";
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
        public final Action1<HttpRequest> _rewriteRequest;
        public final Action1<HttpResponse> _rewriteResponse;
        public final Func1<HttpRequest, Boolean> _needAuthorization;
        public final Func1<HttpRequest,FullHttpResponse> _shortResponse;
        
        public MatchResult(final URI[] uris, 
                final Action1<HttpRequest> rewriteRequest,
                final Action1<HttpResponse> rewriteResponse, 
                final Func1<HttpRequest, Boolean> needAuthorization, 
                final Func1<HttpRequest,FullHttpResponse> shortResponse) {
            this._uris = uris;
            this._rewriteRequest = rewriteRequest;
            this._rewriteResponse = rewriteResponse;
            this._needAuthorization = needAuthorization;
            this._shortResponse = shortResponse;
        }
    }
    
    public int getPriority();

    public void addRule(final RouteRule rule);
    
    public void removeRule(final RouteRule rule);
    
    public void addRequestRewriter(final RequestRewriter rewriter);
    
    public void removeRequestRewriter(final RequestRewriter rewriter);
    
    public void addResponseRewriter(final ResponseRewriter rewriter);
    
    public void removeResponseRewriter(final ResponseRewriter rewriter);
    
    public void addPathAuthorizer(final PathAuthorizer authorizer);
    
    public void removePathAuthorizer(final PathAuthorizer authorizer);
    
    public void addResponser(final Responser responser);
    
    public void removeResponser(final Responser responser);
    
    public MatchResult match(final RoutingInfo info);

    public Collection<String> getRules();
}
