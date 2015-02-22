/**
 * 
 */
package org.jocean.xharbor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.apache.commons.io.FilenameUtils;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.jocean.xharbor.UnitAdminMXBean.UnitMXBean;
import org.jocean.xharbor.util.ZKUpdater.Operator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;

/**
 * @author isdom
 *
 */
public class UnitOperator implements Operator<Object> {

    private static final Logger LOG = LoggerFactory
            .getLogger(UnitOperator.class);
    
    public UnitOperator(final UnitAdmin unitAdmin) {
        this._unitAdmin = unitAdmin;
    }
    
    @Override
    public Object createContext() {
        return null;
    }

    /**
     * @param data
     * @throws IOException
     */
    private Properties loadProperties(final byte[] data) throws IOException {
        try (
            final InputStream is = null != data
                    ? new ByteArrayInputStream(data) 
                    : null;
        ) {
            return new Properties() {
                private static final long serialVersionUID = 1L;
            {
                if (null != is) {
                    this.load( is );
                }
            }};
        }
    }
    
    @Override
    public Object doAdd(
            final Object ctx, 
            final String root, 
            final TreeCacheEvent event)
            throws Exception {
        final ChildData data = event.getData();
        final String pathName = parseSourceFromPath(root, data.getPath());
        if ( null != pathName ) {
            if ( LOG.isDebugEnabled()) {
                LOG.debug("creating unit named {}", pathName);
            }
            final UnitMXBean unit = 
                this._unitAdmin.createUnit(
                        pathName,
                        "**"+ getTemplateFromFullPathName(pathName) + ".xml",
                        Maps.fromProperties(loadProperties(data.getData())),
                        true);
            if (null == unit) {
                LOG.info("create unit {} failed.", pathName);
            } else {
                LOG.info("create unit {} success with active status:{}", pathName, unit.isActive());
            }
            
        }
        return ctx;
    }

    @Override
    public Object doUpdate(
            final Object ctx, 
            final String root, 
            final TreeCacheEvent event)
            throws Exception {
        final ChildData data = event.getData();
        final String pathName = parseSourceFromPath(root, data.getPath());
        if ( null != pathName ) {
            if ( LOG.isDebugEnabled()) {
                LOG.debug("updating unit named {}", pathName);
            }
            final UnitMXBean unit = 
                this._unitAdmin.updateUnit(
                        pathName,
                        Maps.fromProperties(loadProperties(data.getData())));
            if (null == unit) {
                LOG.info("update unit {} failed.", pathName);
            } else {
                LOG.info("update unit {} success with active status:{}", pathName, unit.isActive());
            }
        }
        return ctx;
    }
    
    @Override
    public Object doRemove(
            final Object ctx, 
            final String root, 
            final TreeCacheEvent event)
            throws Exception {
        final ChildData data = event.getData();
        final String pathName = parseSourceFromPath(root, data.getPath());
        if (null != pathName) {
            if ( LOG.isDebugEnabled()) {
                LOG.debug("removing unit for {}", pathName);
            }
            if ( this._unitAdmin.deleteUnit(pathName) ) {
                LOG.info("remove unit {} success", pathName);
            }
            else {
                LOG.info("remove unit {} failure", pathName);
            }
        }
        return ctx;
    }

    @Override
    public Object applyContext(final Object ctx) {
        return ctx;
    }

    private String parseSourceFromPath(final String root, final String path) {
        if (path.length() <= root.length() ) {
            return null;
        }
        return path.substring(root.length() + ( !root.endsWith("/") ? 1 : 0 ));
    }

    private String getTemplateFromFullPathName(final String fullPathName) {
//        a/b/c.txt --> c.txt
//        a.txt     --> a.txt
//        a/b/c     --> c
//        a/b/c/    --> ""
        // for return value, eg:
        //  and gateway.1.2.3.4 --> gateway
        final String  template = FilenameUtils.getName(fullPathName);
        final int idx = template.indexOf('.');
        return ( -1 != idx ) ? template.substring(0, idx) : template;
    }
    
    private final UnitAdmin _unitAdmin;
}
