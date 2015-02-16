/**
 * 
 */
package org.jocean.xharbor.route;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jocean.httpclient.api.GuideBuilder;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Function;
import org.jocean.idiom.Pair;
import org.jocean.idiom.Triple;
import org.jocean.xharbor.api.Dispatcher;
import org.jocean.xharbor.api.Router;
import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.api.ServiceMemo;
import org.jocean.xharbor.spi.HttpRequestTransformer;
import org.jocean.xharbor.util.RulesMXBean;
import org.jocean.xharbor.util.TargetSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.BaseEncoding;

/**
 * @author isdom
 *
 */
public class RoutingInfo2Dispatcher implements Cloneable, Router<RoutingInfo, Dispatcher>, RulesMXBean {
    
    private static final Logger LOG = LoggerFactory
            .getLogger(RoutingInfo2Dispatcher.class);

    private static final URI[] EMPTY_URIS = new URI[0];
    private static final Function<String, String> NOP_REWRITEPATH = new Function<String, String>() {
        @Override
        public String apply(final String input) {
            return input;
        }
        @Override
        public String toString() {
            return "NOP";
        }};
    private static final Function<HttpRequest, Boolean> NOP_NEEDAUTHORIZATION = new Function<HttpRequest, Boolean>() {
        @Override
        public Boolean apply(final HttpRequest request) {
            return false;
        }
        @Override
        public String toString() {
            return "NOP";
        }};
    private static final TargetSet EMPTY_TARGETSET = 
            new TargetSet(null, EMPTY_URIS, false, false, NOP_REWRITEPATH, NOP_NEEDAUTHORIZATION, null, null);

    private static class MatchResult {
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
    
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((_levels == null) ? 0 : _levels.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        RoutingInfo2Dispatcher other = (RoutingInfo2Dispatcher) obj;
        if (_levels == null) {
            if (other._levels != null)
                return false;
        } else if (!_levels.equals(other._levels))
            return false;
        return true;
    }

    public RoutingInfo2Dispatcher(
            final GuideBuilder guideBuilder,
            final ServiceMemo serviceMemo,
            final HttpRequestTransformer.Builder transformerBuilder
            ) {
        this._guideBuilder = guideBuilder;
        this._serviceMemo = serviceMemo;
        this._transformerBuilder = transformerBuilder;
    }
    
    @Override
    protected RoutingInfo2Dispatcher clone() throws CloneNotSupportedException {
        final RoutingInfo2Dispatcher cloned = 
                new RoutingInfo2Dispatcher(this._guideBuilder, this._serviceMemo, this._transformerBuilder);
        for ( Level level : this._levels ) {
            cloned._levels.add(level.clone());
        }
        return cloned;
    }

    @Override
    public String[] getRoutingRules() {
        return new ArrayList<String>() {
            private static final long serialVersionUID = 1L;
        {
            final Iterator<Level> itr = _levels.iterator();
            while (itr.hasNext()) {
                this.addAll(itr.next().getRules());
            }
        }}.toArray(new String[0]);
    }
    
    @Override
    public Dispatcher calculateRoute(final RoutingInfo info, final Context routectx) {
        final Iterator<Level> itr = _levels.iterator();
        while (itr.hasNext()) {
            final Level level = itr.next();
            final MatchResult result = level.match(info);
            if (null != result) {
                return new TargetSet(
                        this._guideBuilder,
                        result._uris, 
                        result._isCheckResponseStatus, 
                        result._isShowInfoLog, 
                        result._rewritePath, 
                        result._needAuthorization, 
                        this._serviceMemo,
                        this._transformerBuilder);
            }
        }
        return EMPTY_TARGETSET;
    }

    public RoutingInfo2Dispatcher freeze() {
        this._isFrozen = true;
        return  this;
    }
    
    public RoutingInfo2Dispatcher addOrUpdateDetail(
            final int priority, 
            final boolean isCheckResponseStatus, 
            final boolean isShowInfoLog, 
            final List<Pair<Pattern,String>> rewritePaths,
            final List<Triple<Pattern,String,String>> authorizations
            )
            throws Exception {
        if ( !this._isFrozen ) {
            getOrCreateLevel(priority)
                .setIsCheckResponseStatus(isCheckResponseStatus)
                .setIsShowInfoLog(isShowInfoLog)
                .setRewritePaths(rewritePaths)
                .setAuthorizations(authorizations);
            return  this;
        } else {
            return this.clone().addOrUpdateDetail(
                    priority, 
                    isCheckResponseStatus, 
                    isShowInfoLog, 
                    rewritePaths, 
                    authorizations);
        }
    }
    
    public RoutingInfo2Dispatcher addOrUpdateRule(final int priority, final String uri, final RoutingInfo[] infoRegexs)
            throws Exception {
        if ( !this._isFrozen ) {
            getOrCreateLevel(priority).addOrUpdateRule(uri, infoRegexs);
            return  this;
        } else {
            return this.clone().addOrUpdateRule(priority, uri, infoRegexs);
        }
    }
    
    public RoutingInfo2Dispatcher removeRule(final int priority, final String uri) 
            throws Exception {
        if ( !this._isFrozen ) {
            getOrCreateLevel(priority).removeRule(uri);
            return  this;
        } else {
            return this.clone().removeRule(priority, uri);
        }
    }
    
    public RoutingInfo2Dispatcher removeLevel(final int priority) 
            throws Exception {
        if ( !this._isFrozen ) {
            doRemoveLevel(priority);
            return  this;
        } else {
            return this.clone().removeLevel(priority);
        }
    }
    
    private Level getOrCreateLevel(final int priority) {
        final Iterator<Level> itr = this._levels.iterator();
        while (itr.hasNext()) {
            final Level level = itr.next();
            if ( level.getPriority() == priority ) {
                return level;
            }
        }
        final Level level = new Level(priority);
        this._levels.add(level);
        return level;
    }

    private void doRemoveLevel(final int priority) {
        final Iterator<Level> itr = this._levels.iterator();
        while (itr.hasNext()) {
            final Level level = itr.next();
            if ( level.getPriority() == priority ) {
                this._levels.remove(level);
                break;
            }
        }
    }

    static class Level implements Cloneable, Comparable<Level> {

        @Override
        public Level clone() throws CloneNotSupportedException {
            final Level cloned = new Level(this._priority);
            cloned._rules.putAll(this._rules);
            cloned._rewritePaths.addAll(this._rewritePaths);
            cloned._authorizations.addAll(this._authorizations);
            return cloned;
        }

        public Level(final int priority) {
            this._priority = priority;
        }
        
        @Override
        public int compareTo(final Level o) {
            return o._priority - this._priority;
        }
        
        public int getPriority() {
            return this._priority;
        }

        public void addOrUpdateRule(final String uri, final RoutingInfo[] infoRegexs) 
                throws Exception {
            final Pattern[] methodPatterns = new Pattern[infoRegexs.length];
            final Pattern[] pathPatterns = new Pattern[infoRegexs.length];
            for ( int idx = 0; idx < infoRegexs.length; idx++) {
                methodPatterns[idx] = safeCompilePattern(infoRegexs[idx].getMethod());
                pathPatterns[idx] = safeCompilePattern(infoRegexs[idx].getPath());
            }
            this._rules.put(new URI(uri), Pair.of(methodPatterns, pathPatterns));
        }

        public void removeRule(final String uri) throws Exception {
            this._rules.remove(new URI(uri));
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
        
        /**
         * @param regex
         * @return
         */
        private static Pattern safeCompilePattern(final String regex) {
            return null != regex ? Pattern.compile(regex) : null;
        }
        
        private MatchResult match(final RoutingInfo info) {
            final List<URI> ret = new ArrayList<URI>();
            
            for ( Map.Entry<URI, Pair<Pattern[],Pattern[]>> entry : this._rules.entrySet() ) {
                final Pattern[] methodPatterns = entry.getValue().getFirst();
                final Pattern[] pathPatterns = entry.getValue().getSecond();
                for (int idx = 0; idx < methodPatterns.length; idx++) {
                    final Pattern methodPattern = methodPatterns[idx];
                    final Pattern pathPattern = pathPatterns[idx];
                    if ( isMatched(methodPattern, info.getMethod()) 
                            && isMatched(pathPattern, info.getPath()) ) {
                        ret.add(entry.getKey());
                        break;
                    }
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

        /**
         * @param pattern
         * @param content
         * @return
         */
        private static boolean isMatched(final Pattern pattern, final String content) {
            return pattern != null ? pattern.matcher(content).find() : true;
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
                final String userAndPass = new String(BaseEncoding.base64().decode(userAndPassBase64Encoded), "UTF-8");
                final String[] fields = userAndPass.split(":");
                return fields.length == 2 ? Pair.of(fields[0], fields[1]) : null;
            } catch (UnsupportedEncodingException e) {
                LOG.warn("exception when getUserAndPassForBasicAuth({}), detail:{}", 
                        userAndPassBase64Encoded, ExceptionUtils.exception2detail(e));
                return null;
            }
        }

        private Collection<String> getRules() {
            return new ArrayList<String>() {
                private static final long serialVersionUID = 1L;
            {
                for ( Map.Entry<URI, Pair<Pattern[],Pattern[]>> entry : _rules.entrySet() ) {
                    final Pair<Pattern[],Pattern[]> pair = entry.getValue();
                    this.add(Integer.toString(_priority) + ":" + entry.getKey().toString() 
                            + ":methods" + Arrays.toString(pair.getFirst())
                            + ",paths" + Arrays.toString(pair.getSecond())
                    ); 
                }
            }};
        }
        
        private final int _priority;
        
        private volatile boolean _isCheckResponseStatus;
        
        private volatile boolean _isShowInfoLog;
        
        private final Map<URI, Pair<Pattern[],Pattern[]>> _rules = 
                new HashMap<URI, Pair<Pattern[],Pattern[]>>();
        
        private final List<Pair<Pattern,String>> _rewritePaths = 
                new ArrayList<Pair<Pattern,String>>();
        
        private final List<Triple<Pattern,String,String>> _authorizations = 
                new ArrayList<Triple<Pattern,String,String>>();
    }

    private final GuideBuilder _guideBuilder;
    private final ServiceMemo _serviceMemo;
    private final HttpRequestTransformer.Builder _transformerBuilder;
    private final SortedSet<Level> _levels = new TreeSet<Level>();
    private transient boolean _isFrozen = false;
}
