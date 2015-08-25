package org.jocean.xharbor.routing.impl;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jocean.http.Feature;
import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.api.Target;
import org.jocean.xharbor.router.DefaultRouter;
import org.jocean.xharbor.routing.AuthorizationRule;
import org.jocean.xharbor.routing.RewriteRequestRule;
import org.jocean.xharbor.routing.RewriteResponseRule;
import org.jocean.xharbor.routing.RespondRule;
import org.jocean.xharbor.routing.RuleSet;
import org.jocean.xharbor.routing.ForwardRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;

public class DefaultRuleSet implements RuleSet {
    
    @SuppressWarnings("unused")
    private static final Logger LOG = LoggerFactory
            .getLogger(DefaultRuleSet.class);
    
    static final URI[] FAKE_URI = new URI[1];
    static final Target[] FAKE_TARGETS = new Target[1];
    
    static {
        try {
            FAKE_URI[0] = new URI("http://255.255.255.255");
        } catch (Exception e) {
        }
        FAKE_TARGETS[0] = new Target() {
            @Override
            public URI serviceUri() {
                return FAKE_URI[0];
            }

            @Override
            public Func0<Feature[]> features() {
                return null;
            }

            @Override
            public String toString() {
                return "FAKE_TARGET";
            }};
    }
    
    public DefaultRuleSet(final int priority, final DefaultRouter router) {
        this._priority = priority;
        this._router = router;
        this._router.addRules(this);
    }
    
    public void stop() {
        this._router.removeRules(this);
    }
    
    @Override
    public int compareTo(final RuleSet o) {
        return o.getPriority() - this._priority;
    }
    
    public int getPriority() {
        return this._priority;
    }

    public void addForward(final ForwardRule forward) {
        this._forwards.add(forward);
        doReset();
    }
    
    public void removeForward(final ForwardRule forward) {
        this._forwards.remove(forward);
        doReset();
    }
    
    public void addRequestRewriter(final RewriteRequestRule rewriter) {
        this._requestRewriters.add(rewriter);
        doReset();
    }
    
    public void removeRequestRewriter(final RewriteRequestRule rewriter) {
        this._requestRewriters.remove(rewriter);
        doReset();
    }
    
    @Override
    public void addResponseRewriter(final RewriteResponseRule rewriter) {
        this._responseRewriters.add(rewriter);
        doReset();
    }

    @Override
    public void removeResponseRewriter(final RewriteResponseRule rewriter) {
        this._responseRewriters.remove(rewriter);
        doReset();
    }
    
    public void addAuthorization(final AuthorizationRule authorizer) {
        this._authorizations.add(authorizer);
        doReset();
    }
    
    public void removeAuthorization(final AuthorizationRule authorizer) {
        this._authorizations.remove(authorizer);
        doReset();
    }
    
    @Override
    public void addRespond(final RespondRule respond) {
        this._responds.add(respond);
        doReset();
    }

    @Override
    public void removeRespond(final RespondRule respond) {
        this._responds.remove(respond);
        doReset();
    }
    
    @Override
    public MatchResult match(final RoutingInfo info) {
        final Func1<HttpRequest, FullHttpResponse> responser = genResponser(info);
        if (null!=responser) {
            return new MatchResult(FAKE_TARGETS, 
                    NOP_REQ_REWRITER, 
                    NOP_RESP_REWRITER, 
                    NOP_AUTHORIZATION,
                    responser);
        }
        
        final List<Target> targets = new ArrayList<>();
        
        for (ForwardRule forward : this._forwards) {
            final Target target = forward.match(info);
            if (null!=target) {
                targets.add(target);
            }
        }
        return !targets.isEmpty() 
            ? new MatchResult(targets.toArray(EMPTY_TARGETS), 
                    genRewriteRequest(info), 
                    genRewriteResponse(info), 
                    genAuthorization(info),
                    null)
            : null;
    }

    private Func1<HttpRequest, FullHttpResponse> genResponser(final RoutingInfo info) {
        for (RespondRule responser : this._responds) {
            final Func1<HttpRequest, FullHttpResponse> func = responser.genResponser(info);
            if (null!=func) {
                return func;
            }
        }
        return null;
    }

    private Action1<HttpRequest> genRewriteRequest(final RoutingInfo info) {
        for (RewriteRequestRule rewriter : this._requestRewriters) {
            final Action1<HttpRequest> func = rewriter.genRewriting(info);
            if (null!=func) {
                return func;
            }
        }
        return NOP_REQ_REWRITER;
    }
    
    private Action1<HttpResponse> genRewriteResponse(final RoutingInfo info) {
        for (RewriteResponseRule rewriter : this._responseRewriters) {
            final Action1<HttpResponse> func = rewriter.genRewriting(info);
            if (null!=func) {
                return func;
            }
        }
        return NOP_RESP_REWRITER;
    }
    
    private Func1<HttpRequest, Boolean> genAuthorization(final RoutingInfo info) {
        for (AuthorizationRule authorizer : this._authorizations) {
            final Func1<HttpRequest, Boolean> func = authorizer.genAuthorization(info);
            if (null!=func) {
                return func;
            }
        }
        return NOP_AUTHORIZATION;
    }

    @Override
    public Collection<String> getRules() {
        return new ArrayList<String>() {
            private static final long serialVersionUID = 1L;
        {
            for (ForwardRule rule : _forwards) {
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
    
    private Action0 _resetAction;
    
    private final List<ForwardRule> _forwards = 
            new CopyOnWriteArrayList<ForwardRule>();
    
    private final List<RewriteRequestRule> _requestRewriters = 
            new CopyOnWriteArrayList<RewriteRequestRule>();
    
    private final List<RewriteResponseRule> _responseRewriters = 
            new CopyOnWriteArrayList<RewriteResponseRule>();
    
    private final List<AuthorizationRule> _authorizations = 
            new CopyOnWriteArrayList<AuthorizationRule>();

    private final List<RespondRule> _responds = 
            new CopyOnWriteArrayList<RespondRule>();
}
