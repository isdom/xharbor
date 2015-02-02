/**
 * 
 */
package org.jocean.xharbor.transform;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;

import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Pair;
import org.jocean.idiom.Visitor;
import org.jocean.xharbor.util.ZKUpdater.Operator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.annotation.JSONField;

/**
 * @author isdom
 *
 */
public class AUPOperator implements Operator<CompositeAUPBuilder> {

    private static final Logger LOG = LoggerFactory
            .getLogger(AUPOperator.class);
    
    public AUPOperator(final Visitor<CompositeAUPBuilder> updateAPUBuilder) {
        this._updateAPUBuilder = updateAPUBuilder;
    }
    
    @Override
    public CompositeAUPBuilder createContext() {
        return new CompositeAUPBuilder();
    }

    @Override
    public CompositeAUPBuilder doAddOrUpdate(
            final CompositeAUPBuilder builder,
            final String root, 
            final TreeCacheEvent event) throws Exception {
        final ChildData data = event.getData();
        final Pair<String,AUPDesc> pair = parseFromPath(root, data.getPath(), data.getData());
        if (null != pair) {
            final String path = pair.getFirst();
            final AUPDesc desc = pair.getSecond();
            if ( LOG.isDebugEnabled()) {
                LOG.debug("add or update builder with {}/{}",  path, desc);
            }
            return builder.addOrUpdateAUP(path, desc._noapply, desc._applyKeys);
        }
        return null;
    }

    @Override
    public CompositeAUPBuilder doRemove(
            final CompositeAUPBuilder builder,
            final String root, 
            final TreeCacheEvent event) throws Exception {
        final ChildData data = event.getData();
        final Pair<String,AUPDesc> pair = parseFromPath(root, data.getPath(), null);
        if (null != pair ) {
            final String path = pair.getFirst();
            if ( LOG.isDebugEnabled()) {
                LOG.debug("remove builder with {}", path);
            }
            return builder.removeAUP(path);
        }
        return null;
    }

    @Override
    public CompositeAUPBuilder applyContext(final CompositeAUPBuilder builder) {
        if ( null != builder ) {
            try {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("try to update builder for ({})", builder);
                }
                this._updateAPUBuilder.visit(builder.freeze());
            } catch (Exception e) {
                LOG.warn("exception when update builder {} via ({}), detail:{}",
                        builder, this._updateAPUBuilder, ExceptionUtils.exception2detail(e));
            }
        }
        return builder;
    }

    public static class AUPDesc {
        
        @Override
        public String toString() {
            return "AUPDesc [_noapply=" + _noapply + ", _applyKeys="
                    + Arrays.toString(_applyKeys) + "]";
        }

        private String _noapply;
        private String[] _applyKeys;
        
        @JSONField(name="noapply")
        public String getNoapply() {
            return _noapply;
        }

        @JSONField(name="noapply")
        public void setNoapply(final String noapply) {
            this._noapply = noapply;
        }

        @JSONField(name="applykeys")
        public String[] getApplyKeys() {
            return this._applyKeys;
        }
        
        @JSONField(name="applykeys")
        public void setApplyKeys(String[] keys) {
            this._applyKeys = keys;
        }
    }
    
    private Pair<String,AUPDesc> parseFromPath(final String root, final String path, final byte[] data) {
        if (path.length() <= root.length()) {
            return null;
        }
        final String uriPath = path.substring(root.length() + ( !root.endsWith("/") ? 1 : 0 ));
        final String replaced = uriPath.replaceAll("\\|","/");
        
        try {
            return Pair.of(replaced, parseAUPDesc(data));
        }
        catch (Exception e) {
            LOG.warn("exception when parse from path {}, detail:{}", 
                    path, ExceptionUtils.exception2detail(e));
        }
        return null;
    }

    /**
     * @param data
     * @return
     * @throws UnsupportedEncodingException
     */
    private AUPDesc parseAUPDesc(final byte[] data)
            throws UnsupportedEncodingException {
        if (null == data || data.length == 0) {
            return null;
        }
        return JSON.parseObject(new String(data,  "UTF-8"), AUPDesc.class);
    }
    
    private final Visitor<CompositeAUPBuilder> _updateAPUBuilder;
}
