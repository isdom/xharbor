package org.jocean.xharbor.routing.impl;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.router.DefaultRouter;
import org.jocean.xharbor.routing.PathAuthorizer;
import org.jocean.xharbor.routing.PathRewriter;
import org.jocean.xharbor.routing.Responser;
import org.jocean.xharbor.routing.RouteLevel;
import org.jocean.xharbor.routing.RouteRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.functions.Func1;

public class DefaultLevel implements RouteLevel {
    
    @SuppressWarnings("unused")
    private static final Logger LOG = LoggerFactory
            .getLogger(DefaultLevel.class);
    
    static final URI[] FAKE_URIS = new URI[1];
    
    static {
        try {
            FAKE_URIS[0] = new URI("http://255.255.255.255");
        } catch (Exception e) {
        }
    }
    
    public DefaultLevel(final int priority, final DefaultRouter router) {
        this._priority = priority;
        this._router = router;
        this._router.addLevel(this);
    }
    
    public void stop() {
        this._router.removeLevel(this);
    }
    
    @Override
    public int compareTo(final RouteLevel o) {
        return o.getPriority() - this._priority;
    }
    
    public int getPriority() {
        return this._priority;
    }

    public void addRule(final RouteRule rule) {
        this._rules.add(rule);
    }
    
    public void removeRule(final RouteRule rule) {
        this._rules.remove(rule);
    }
    
    public DefaultLevel setIsCheckResponseStatus(final boolean isCheckResponseStatus) {
        this._isCheckResponseStatus = isCheckResponseStatus;
        return this;
    }

    public void addPathRewriter(final PathRewriter rewriter) {
        this._rewritePaths.add(rewriter);
    }
    
    public void removePathRewriter(final PathRewriter rewriter) {
        this._rewritePaths.remove(rewriter);
    }
    
    public void addPathAuthorizer(final PathAuthorizer authorizer) {
        this._authorizations.add(authorizer);
    }
    
    public void removePathAuthorizer(final PathAuthorizer authorizer) {
        this._authorizations.remove(authorizer);
    }
    
    @Override
    public void addResponser(final Responser responser) {
        this._responsers.add(responser);
    }

    @Override
    public void removeResponser(final Responser responser) {
        this._responsers.remove(responser);
    }
    
    @Override
    public MatchResult match(final RoutingInfo info) {
        final Func1<HttpRequest, FullHttpResponse> shortResponse = genShortResponse(info);
        if (null!=shortResponse) {
            return new MatchResult(FAKE_URIS, 
                    false,
                    NOP_REWRITEPATH, 
                    NOP_NEEDAUTHORIZATION,
                    shortResponse);
        }
        
        final List<URI> ret = new ArrayList<URI>();
        
        for (RouteRule rule : this._rules) {
            final URI uri = rule.match(info);
            if (null!=uri) {
                ret.add(uri);
            }
        }
        return !ret.isEmpty() 
            ? new MatchResult(ret.toArray(EMPTY_URIS), 
                    this._isCheckResponseStatus,
                    genRewritePath(info.getPath()), 
                    genNeedAuthorization(info.getPath()),
                    null)
            : null;
    }

    private Func1<HttpRequest, FullHttpResponse> genShortResponse(final RoutingInfo info) {
        for (Responser responser : this._responsers) {
            final Func1<HttpRequest, FullHttpResponse> func = responser.genShortResponse(info);
            if (null!=func) {
                return func;
            }
        }
        return null;
    }

    private Func1<String, String> genRewritePath(final String path) {
        for (PathRewriter rewriter : this._rewritePaths) {
            final Func1<String, String> func = rewriter.genRewriting(path);
            if (null!=func) {
                return func;
            }
        }
        return NOP_REWRITEPATH;
    }
    
    private Func1<HttpRequest, Boolean> genNeedAuthorization(final String path) {
        for (PathAuthorizer authorizer : this._authorizations) {
            final Func1<HttpRequest, Boolean> func = authorizer.genNeedAuthorization(path);
            if (null!=func) {
                return func;
            }
        }
        return NOP_NEEDAUTHORIZATION;
    }

    @Override
    public Collection<String> getRules() {
        return new ArrayList<String>() {
            private static final long serialVersionUID = 1L;
        {
            for (RouteRule rule : _rules) {
                this.add(Integer.toString(_priority) + ":" + rule.toString());
            }
        }};
    }
    
    private final DefaultRouter _router;
    
    private final int _priority;
    
    private volatile boolean _isCheckResponseStatus;
    
    private final List<RouteRule> _rules = 
            new CopyOnWriteArrayList<RouteRule>();
    
    private final List<PathRewriter> _rewritePaths = 
            new CopyOnWriteArrayList<PathRewriter>();
    
    private final List<PathAuthorizer> _authorizations = 
            new CopyOnWriteArrayList<PathAuthorizer>();

    private final List<Responser> _responsers = 
            new CopyOnWriteArrayList<Responser>();
}
