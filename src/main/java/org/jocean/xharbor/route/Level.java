package org.jocean.xharbor.route;

import io.netty.handler.codec.http.HttpRequest;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jocean.idiom.Function;
import org.jocean.xharbor.api.RoutingInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Level implements Comparable<Level> {
    
    @SuppressWarnings("unused")
    private static final Logger LOG = LoggerFactory
            .getLogger(Level.class);
    
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
    
    static class MatchResult {
        final URI[] _uris;
        final boolean _isCheckResponseStatus;
        final boolean _isShowInfoLog;
        final Function<String, String> _rewritePath;
        final Function<HttpRequest, Boolean> _needAuthorization;
        
        MatchResult(final URI[] uris, 
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
    
    public Level(final int priority, final DefaultRouter router) {
        this._priority = priority;
        this._router = router;
        this._router.addLevel(this);
    }
    
    public void stop() {
        this._router.removeLevel(this);
    }
    
    @Override
    public int compareTo(final Level o) {
        return o._priority - this._priority;
    }
    
    public int getPriority() {
        return this._priority;
    }

    public void addRule(final Rule rule) {
        this._rules.add(rule);
    }
    
    public void removeRule(final Rule rule) {
        this._rules.remove(rule);
    }
    
    public Level setIsCheckResponseStatus(final boolean isCheckResponseStatus) {
        this._isCheckResponseStatus = isCheckResponseStatus;
        return this;
    }

    public Level setIsShowInfoLog(final boolean isShowInfoLog) {
        this._isShowInfoLog = isShowInfoLog;
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
    
    MatchResult match(final RoutingInfo info) {
        final List<URI> ret = new ArrayList<URI>();
        
        for (Rule rule : this._rules) {
            final URI uri = rule.match(info);
            if (null!=uri) {
                ret.add(uri);
            }
        }
        return !ret.isEmpty() 
            ? new MatchResult(ret.toArray(EMPTY_URIS), 
                    this._isCheckResponseStatus,
                    this._isShowInfoLog,
                    genRewritePath(info.getPath()), 
                    genNeedAuthorization(info.getPath()))
            : null;
    }

    private Function<String, String> genRewritePath(final String path) {
        for (PathRewriter rewriter : this._rewritePaths) {
            final Function<String, String> func = rewriter.genRewriter(path);
            if (null!=func) {
                return func;
            }
        }
        return NOP_REWRITEPATH;
    }
    
    private Function<HttpRequest, Boolean> genNeedAuthorization(final String path) {
        for (PathAuthorizer authorizer : this._authorizations) {
            final Function<HttpRequest, Boolean> func = authorizer.genNeedAuthorization(path);
            if (null!=func) {
                return func;
            }
        }
        return NOP_NEEDAUTHORIZATION;
    }

    Collection<String> getRules() {
        return new ArrayList<String>() {
            private static final long serialVersionUID = 1L;
        {
            for (Rule rule : _rules) {
                this.add(Integer.toString(_priority) + ":" + rule.toString());
            }
        }};
    }
    
    private final DefaultRouter _router;
    
    private final int _priority;
    
    private volatile boolean _isCheckResponseStatus;
    
    private volatile boolean _isShowInfoLog;
    
    private final List<Rule> _rules = 
            new CopyOnWriteArrayList<Rule>();
    
    private final List<PathRewriter> _rewritePaths = 
            new CopyOnWriteArrayList<PathRewriter>();
    
    private final List<PathAuthorizer> _authorizations = 
            new CopyOnWriteArrayList<PathAuthorizer>();

}
