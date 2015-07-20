package org.jocean.xharbor.route;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Function;
import org.jocean.idiom.Pair;
import org.jocean.idiom.Triple;
import org.jocean.xharbor.api.RoutingInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.BaseEncoding;

public class Level implements Comparable<Level> {
    
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

    public Level setRewritePaths(final List<Pair<Pattern, String>> rewritePaths) {
        this._rewritePaths.clear();
        this._rewritePaths.addAll(rewritePaths);
        return this;
    }
    
    public Level setAuthorizations(final List<Triple<Pattern,String,String>> authorizations) {
        this._authorizations.clear();
        this._authorizations.addAll(authorizations);
        return this;
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
        for (Pair<Pattern,String> pair : this._rewritePaths) {
            final Pattern pattern = pair.getFirst();
            final Matcher matcher = pattern.matcher(path);
            if ( matcher.find() ) {
                final String replaceTo = pair.getSecond();
                return new Function<String, String>() {
                    @Override
                    public String apply(final String input) {
                        return pattern.matcher(input).replaceFirst(replaceTo);
                    }
                    
                    @Override
                    public String toString() {
                        return "[" + pattern.toString() + "->" + replaceTo + "]";
                    }};
            }
        }
        return NOP_REWRITEPATH;
    }
    
    private Function<HttpRequest, Boolean> genNeedAuthorization(final String path) {
        for (Triple<Pattern,String,String> triple : this._authorizations) {
            final Pattern pattern = triple.getFirst();
            final Matcher matcher = pattern.matcher(path);
            if ( matcher.find() ) {
                final String user = triple.getSecond();
                final String password = triple.getThird();
                return new Function<HttpRequest, Boolean>() {
                    @Override
                    public Boolean apply(final HttpRequest request) {
                        return !isAuthorizeSuccess(request, user, password);
                    }
                    
                    @Override
                    public String toString() {
                        return "[" + pattern.toString() + ":" + user + "/" + password + "]";
                    }};
            }
        }
        return NOP_NEEDAUTHORIZATION;
    }

    private boolean isAuthorizeSuccess(
            final HttpRequest httpRequest, 
            final String authUser, 
            final String authPassword) {
        final String authorization = HttpHeaders.getHeader(httpRequest, HttpHeaders.Names.AUTHORIZATION);
        if ( null != authorization) {
            final String userAndPassBase64Encoded = validateBasicAuth(authorization);
            if ( null != userAndPassBase64Encoded ) {
                final Pair<String, String> userAndPass = getUserAndPassForBasicAuth(userAndPassBase64Encoded);
                if (null != userAndPass) {
                    final boolean ret = (userAndPass.getFirst().equals(authUser)
                            && userAndPass.getSecond().equals(authPassword));
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("httpRequest [{}] basic authorization {}, input:{}/{}, setted auth:{}/{}", 
                                httpRequest, (ret ? "success" : "failure"), 
                                userAndPass.getFirst(), userAndPass.getSecond(),
                                authUser, authPassword);
                    }
                    return ret;
                }
            }
        }
        return false;
    }

    private static String validateBasicAuth(final String authorization) {
        if (authorization.startsWith("Basic")) {
            final String[] authFields = authorization.split(" ");
            if (authFields.length>=2) {
                return authFields[1];
            }
        }
        return null;
    }
    
    private static Pair<String, String> getUserAndPassForBasicAuth(
            final String userAndPassBase64Encoded) {
        try {
            final String userAndPass = new String(BaseEncoding.base64().decode(userAndPassBase64Encoded), 
                    "UTF-8");
            final String[] fields = userAndPass.split(":");
            return fields.length == 2 ? Pair.of(fields[0], fields[1]) : null;
        } catch (UnsupportedEncodingException e) {
            LOG.warn("exception when getUserAndPassForBasicAuth({}), detail:{}", 
                    userAndPassBase64Encoded, ExceptionUtils.exception2detail(e));
            return null;
        }
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
    
    private final List<Pair<Pattern,String>> _rewritePaths = 
            new ArrayList<Pair<Pattern,String>>();
    
    private final List<Triple<Pattern,String,String>> _authorizations = 
            new ArrayList<Triple<Pattern,String,String>>();

}
