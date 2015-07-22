package org.jocean.xharbor.routing;

import io.netty.handler.codec.http.HttpRequest;

import java.net.URI;
import java.util.Collection;

import org.jocean.idiom.Function;
import org.jocean.xharbor.api.RoutingInfo;

public interface RouteLevel extends Comparable<RouteLevel> {
    
    static final URI[] EMPTY_URIS = new URI[0];
    
    static final Function<String, String> NOP_REWRITEPATH = new Function<String, String>() {
        @Override
        public String apply(final String input) {
            return input;
        }
        @Override
        public String toString() {
            return "NOP";
        }};
    static final Function<HttpRequest, Boolean> NOP_NEEDAUTHORIZATION = new Function<HttpRequest, Boolean>() {
        @Override
        public Boolean apply(final HttpRequest request) {
            return false;
        }
        @Override
        public String toString() {
            return "NOP";
        }};
        
    public class MatchResult {
        public final URI[] _uris;
        public final boolean _isCheckResponseStatus;
        public final boolean _isShowInfoLog;
        public final Function<String, String> _rewritePath;
        public final Function<HttpRequest, Boolean> _needAuthorization;
        
        public MatchResult(final URI[] uris, 
                final boolean isCheckResponseStatus,
                final boolean isShowInfoLog,
                final Function<String, String> rewritePath,
                final Function<HttpRequest, Boolean> needAuthorization) {
            this._uris = uris;
            this._isCheckResponseStatus = isCheckResponseStatus;
            this._isShowInfoLog = isShowInfoLog;
            this._rewritePath = rewritePath;
            this._needAuthorization = needAuthorization;
        }
    }
    
    public int getPriority();

    public void addRule(final RouteRule rule);
    
    public void removeRule(final RouteRule rule);
    
    public void addPathRewriter(final PathRewriter rewriter);
    
    public void removePathRewriter(final PathRewriter rewriter);
    
    public void addPathAuthorizer(final PathAuthorizer authorizer);
    
    public void removePathAuthorizer(final PathAuthorizer authorizer);
    
    public MatchResult match(final RoutingInfo info);

    public Collection<String> getRules();
}
