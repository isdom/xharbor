package org.jocean.xharbor.routing.impl;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.router.DefaultRouter;
import org.jocean.xharbor.routing.PathAuthorizer;
import org.jocean.xharbor.routing.RequestRewriter;
import org.jocean.xharbor.routing.ResponseRewriter;
import org.jocean.xharbor.routing.Responser;
import org.jocean.xharbor.routing.RouteLevel;
import org.jocean.xharbor.routing.RouteRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.functions.Action0;
import rx.functions.Action1;
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
        doReset();
    }
    
    public void removeRule(final RouteRule rule) {
        this._rules.remove(rule);
        doReset();
    }
    
    public DefaultLevel setIsCheckResponseStatus(final boolean isCheckResponseStatus) {
        this._isCheckResponseStatus = isCheckResponseStatus;
        return this;
    }

    public void addRequestRewriter(final RequestRewriter rewriter) {
        this._requestRewriters.add(rewriter);
        doReset();
    }
    
    public void removeRequestRewriter(final RequestRewriter rewriter) {
        this._requestRewriters.remove(rewriter);
        doReset();
    }
    
    @Override
    public void addResponseRewriter(final ResponseRewriter rewriter) {
        this._responseRewriters.add(rewriter);
        doReset();
    }

    @Override
    public void removeResponseRewriter(final ResponseRewriter rewriter) {
        this._responseRewriters.remove(rewriter);
        doReset();
    }
    
    public void addPathAuthorizer(final PathAuthorizer authorizer) {
        this._authorizations.add(authorizer);
        doReset();
    }
    
    public void removePathAuthorizer(final PathAuthorizer authorizer) {
        this._authorizations.remove(authorizer);
        doReset();
    }
    
    @Override
    public void addResponser(final Responser responser) {
        this._responsers.add(responser);
        doReset();
    }

    @Override
    public void removeResponser(final Responser responser) {
        this._responsers.remove(responser);
        doReset();
    }
    
    @Override
    public MatchResult match(final RoutingInfo info) {
        final Func1<HttpRequest, FullHttpResponse> shortResponse = genShortResponse(info);
        if (null!=shortResponse) {
            return new MatchResult(FAKE_URIS, 
                    false,
                    NOP_REQ_REWRITER, 
                    NOP_RESP_REWRITER, 
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
                    genRewriteRequest(info.getPath()), 
                    genRewriteResponse(info.getPath()), 
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

    private Action1<HttpRequest> genRewriteRequest(final String path) {
        for (RequestRewriter rewriter : this._requestRewriters) {
            final Action1<HttpRequest> func = rewriter.genRewriting(path);
            if (null!=func) {
                return func;
            }
        }
        return NOP_REQ_REWRITER;
    }
    
    private Action1<HttpResponse> genRewriteResponse(final String path) {
        for (ResponseRewriter rewriter : this._responseRewriters) {
            final Action1<HttpResponse> func = rewriter.genRewriting(path);
            if (null!=func) {
                return func;
            }
        }
        return NOP_RESP_REWRITER;
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
    
    public void setResetAction(final Action0 resetAction) {
        this._resetAction = resetAction;
    }
    
    private void doReset() {
        if (null!=this._resetAction) {
            this._resetAction.call();
        }
    }

    private final DefaultRouter _router;
    
    private final int _priority;
    
    private volatile boolean _isCheckResponseStatus;
    
    private Action0 _resetAction;
    
    private final List<RouteRule> _rules = 
            new CopyOnWriteArrayList<RouteRule>();
    
    private final List<RequestRewriter> _requestRewriters = 
            new CopyOnWriteArrayList<RequestRewriter>();
    
    private final List<ResponseRewriter> _responseRewriters = 
            new CopyOnWriteArrayList<ResponseRewriter>();
    
    private final List<PathAuthorizer> _authorizations = 
            new CopyOnWriteArrayList<PathAuthorizer>();

    private final List<Responser> _responsers = 
            new CopyOnWriteArrayList<Responser>();
}
