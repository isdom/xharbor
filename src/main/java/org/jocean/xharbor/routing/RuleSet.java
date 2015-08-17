package org.jocean.xharbor.routing;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import java.util.Collection;

import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.api.Target;

import rx.functions.Action1;
import rx.functions.Func1;

public interface RuleSet extends Comparable<RuleSet> {
    
    static final Target[] EMPTY_TARGETS = new Target[0];
    
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
    static final Func1<HttpRequest, Boolean> NOP_AUTHORIZATION = new Func1<HttpRequest, Boolean>() {
        @Override
        public Boolean call(final HttpRequest request) {
            return false;
        }
        @Override
        public String toString() {
            return "NOP";
        }};
        
    public class MatchResult {
        public final Target[] _targets;
        public final Action1<HttpRequest> _rewriteRequest;
        public final Action1<HttpResponse> _rewriteResponse;
        public final Func1<HttpRequest, Boolean> _authorization;
        public final Func1<HttpRequest,FullHttpResponse> _responses;
        
        public MatchResult(final Target[] targets, 
                final Action1<HttpRequest> rewriteRequest,
                final Action1<HttpResponse> rewriteResponse, 
                final Func1<HttpRequest, Boolean> authorization, 
                final Func1<HttpRequest,FullHttpResponse> responser
                ) {
            this._targets = targets;
            this._rewriteRequest = rewriteRequest;
            this._rewriteResponse = rewriteResponse;
            this._authorization = authorization;
            this._responses = responser;
        }
    }
    
    public int getPriority();

    public void addForward(final ForwardRule forward);
    
    public void removeForward(final ForwardRule forward);
    
    public void addRequestRewriter(final RewriteRequestRule rewriter);
    
    public void removeRequestRewriter(final RewriteRequestRule rewriter);
    
    public void addResponseRewriter(final RewriteResponseRule rewriter);
    
    public void removeResponseRewriter(final RewriteResponseRule rewriter);
    
    public void addAuthorization(final AuthorizationRule authorizer);
    
    public void removeAuthorization(final AuthorizationRule authorizer);
    
    public void addRespond(final RespondRule responser);
    
    public void removeRespond(final RespondRule responser);
    
    public MatchResult match(final RoutingInfo info);

    public Collection<String> getRules();
}
