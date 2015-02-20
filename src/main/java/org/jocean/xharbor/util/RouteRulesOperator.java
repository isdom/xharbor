/**
 * 
 */
package org.jocean.xharbor.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.jocean.httpclient.api.GuideBuilder;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Pair;
import org.jocean.idiom.Triple;
import org.jocean.idiom.Visitor;
import org.jocean.xharbor.api.Dispatcher;
import org.jocean.xharbor.api.Router;
import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.api.ServiceMemo;
import org.jocean.xharbor.route.RoutingInfo2Dispatcher;
import org.jocean.xharbor.spi.HttpRequestTransformer;
import org.jocean.xharbor.util.ZKUpdater.Operator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.annotation.JSONField;

/**
 * @author isdom
 *
 */
public class RouteRulesOperator implements Operator<Router<RoutingInfo, Dispatcher>> {

    private static final Logger LOG = LoggerFactory
            .getLogger(RouteRulesOperator.class);
    
    public RouteRulesOperator(
            final Visitor<Router<RoutingInfo, Dispatcher>> updateRules,
            final GuideBuilder guideBuilder,
            final ServiceMemo serviceMemo,
            final HttpRequestTransformer.Builder transformerBuilder
            ) {
        this._updateRules = updateRules;
        this._guideBuilder = guideBuilder;
        this._serviceMemo = serviceMemo;
        this._transformerBuilder = transformerBuilder;
    }
    
    @Override
    public Router<RoutingInfo, Dispatcher> createContext() {
        return new RoutingInfo2Dispatcher(this._guideBuilder, this._serviceMemo, this._transformerBuilder);
    }

    @Override
    public RoutingInfo2Dispatcher doAddOrUpdate(
            final Router<RoutingInfo, Dispatcher> entity, 
            final String root, 
            final TreeCacheEvent event)
            throws Exception {
        final ChildData data = event.getData();
        final Integer level = tryParseLevel(root, data.getPath());
        if ( null != level ) {
            final LevelDesc desc = parseLevelDetail(data.getData());
            if (null != desc ) {
                if ( LOG.isDebugEnabled()) {
                    LOG.debug("add or update level detail with {}/{}", level, desc);
                }
                return ((RoutingInfo2Dispatcher)entity).addOrUpdateDetail(
                        level, 
                        desc.isCheckResponseStatus, 
                        desc.isShowInfoLog, 
                        desc.asRewritePathList(), 
                        desc.asAuthorizationList());
            }
            return null;
        }
        else {
            final Pair<Integer,String> pair = tryParseLevelAndUri(root, data.getPath());
            final RuleDesc desc = parseRules(data.getData());
            if (null != pair && null != desc ) {
                final int priority = pair.getFirst();
                final String uri = pair.getSecond().replace(":||","://");
                if ( LOG.isDebugEnabled()) {
                    LOG.debug("add or update rule with {}/{}/{}", 
                            priority, uri, Arrays.toString( desc.getRegexs()));
                }
                return ((RoutingInfo2Dispatcher)entity).addOrUpdateRule(priority, uri, desc.getRegexs());
            }
            return null;
        }
    }

    @Override
    public RoutingInfo2Dispatcher doRemove(
            final Router<RoutingInfo, Dispatcher> entity, 
            final String root, 
            final TreeCacheEvent event)
            throws Exception {
        final ChildData data = event.getData();
        final Integer level = tryParseLevel(root, data.getPath());
        if ( null != level ) {
            return ((RoutingInfo2Dispatcher)entity).removeLevel(level);
        }
        else {
            final Pair<Integer,String> pair = tryParseLevelAndUri(root, data.getPath());
            if (null != pair ) {
                final int priority = pair.getFirst();
                final String uri = pair.getSecond().replace(":||","://");
                if ( LOG.isDebugEnabled()) {
                    LOG.debug("remove rule with {}/{}", priority, uri);
                }
                return ((RoutingInfo2Dispatcher)entity).removeRule(pair.getFirst(), pair.getSecond().replace(":||", "://"));
            }
            return null;
        }
    }

    @Override
    public Router<RoutingInfo, Dispatcher> applyContext(final Router<RoutingInfo, Dispatcher> entity) {
        if ( null != entity ) {
            try {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("try to update rules for ({})", entity);
                }
                this._updateRules.visit(((RoutingInfo2Dispatcher)entity).freeze());
            } catch (Exception e) {
                LOG.warn("exception when update rules {} via ({}), detail:{}",
                        entity, this._updateRules, ExceptionUtils.exception2detail(e));
            }
        }
        return entity;
    }

    public static class LevelDesc {
        
        public static class RewritePathDesc {

            public String regex;
            public String rewriteTo;
            @Override
            public String toString() {
                return "RewritePathDesc [regex=" + regex + ", rewriteTo="
                        + rewriteTo + "]";
            }
        }
        
        public static class AuthorizationDesc {

            public String regex;
            public String user;
            public String password;
            @Override
            public String toString() {
                return "AuthorizationDesc [regex=" + regex + ", user="
                        + user + ", password=" + password
                        + "]";
            }
        }
        
        @Override
        public String toString() {
            return "LevelDesc [rewritePaths=" + Arrays.toString(rewritePaths)
                    + ", authorizations=" + Arrays.toString(authorizations)
                    + ", isCheckResponseStatus=" + isCheckResponseStatus
                    + ", isShowInfoLog=" + isShowInfoLog + "]";
        }

        public RewritePathDesc[] rewritePaths;
        public AuthorizationDesc[] authorizations;
        public boolean  isCheckResponseStatus = false;
        public boolean  isShowInfoLog = true;
        
        public List<Pair<Pattern, String>> asRewritePathList() {
            return new ArrayList<Pair<Pattern, String>>() {
                private static final long serialVersionUID = 1L;
                {
                    if (null != rewritePaths) {
                        for (RewritePathDesc desc : rewritePaths) {
                            this.add(Pair.of(Pattern.compile(desc.regex), desc.rewriteTo));
                        }
                    }
                }};
        }
        
        public List<Triple<Pattern, String, String>> asAuthorizationList() {
            return new ArrayList<Triple<Pattern, String, String>>() {
                private static final long serialVersionUID = 1L;
                {
                    if (null != authorizations ) {
                        for (AuthorizationDesc desc : authorizations) {
                            this.add(Triple.of(Pattern.compile(desc.regex), desc.user, desc.password));
                        }
                    }
                }};
        }
    }
    
    private Integer tryParseLevel(final String root, final String path) {
        if (path.length() <= root.length() ) {
            return null;
        }
        final String pathToLevel = path.substring(root.length() + ( !root.endsWith("/") ? 1 : 0 ));
        final String[] arrays = pathToLevel.split("/");
        if (arrays.length == 1) {
            return Integer.parseInt(arrays[0]);
        }
        else {
            return null;
        }
    }
    
    private LevelDesc parseLevelDetail(final byte[] data) {
        if (null == data || data.length == 0) {
            return null;
        }
        try {
            return JSON.parseObject(new String(data,  "UTF-8"), LevelDesc.class);
        } catch (Exception e) {
            LOG.warn("exception when parse level detail {}, detail:{}", 
                    Arrays.toString(data), ExceptionUtils.exception2detail(e));
        }
        return null;
    }

    public static class RuleDesc {
        
        public static class InfoDesc implements RoutingInfo {

            public String method;
            public String path;
            
            @Override
            public String getMethod() {
                return method;
            }

            @Override
            public String getPath() {
                return path;
            }
        }
        
        private String descrption;
        private InfoDesc[] regexs;
        
        @JSONField(name="descrption")
        public String getDescrption() {
            return descrption;
        }

        @JSONField(name="descrption")
        public void setDescrption(String descrption) {
            this.descrption = descrption;
        }

        @JSONField(name="regexs")
        public InfoDesc[] getRegexs() {
            return regexs;
        }
        
        @JSONField(name="regexs")
        public void setRegexs(InfoDesc[] regexs) {
            this.regexs = regexs;
        }
    }
    
    private Pair<Integer, String> tryParseLevelAndUri(final String root, final String path) {
        if (path.length() <= root.length() ) {
            return null;
        }
        final String pathToHost = path.substring(root.length() + ( !root.endsWith("/") ? 1 : 0 ));
        final String[] arrays = pathToHost.split("/");
        if ( arrays.length != 2 ) {
            return null;
        }
        try {
            return Pair.of(Integer.parseInt(arrays[0]), arrays[1]);
        }
        catch (Exception e) {
            LOG.warn("exception when parse from path {}, detail:{}", 
                    path, ExceptionUtils.exception2detail(e));
        }
        return null;
    }

    private RuleDesc parseRules(final byte[] data) {
        if (null == data || data.length == 0) {
            return null;
        }
        try {
            return JSON.parseObject(new String(data,  "UTF-8"), RuleDesc.class);
        } catch (Exception e) {
            LOG.warn("exception when parse from data {}, detail:{}", 
                    Arrays.toString(data), ExceptionUtils.exception2detail(e));
        }
        return null;
    }
    
    private final Visitor<Router<RoutingInfo, Dispatcher>> _updateRules;
    private final GuideBuilder _guideBuilder; 
    private final ServiceMemo _serviceMemo; 
    private final HttpRequestTransformer.Builder _transformerBuilder;
}
