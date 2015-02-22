/**
 * 
 */
package org.jocean.xharbor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.io.FilenameUtils;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
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
                LOG.debug("create unit with {}", pathName);
            }
            this._unitAdmin.deleteUnit(pathName);
            
            final String pattern = "**"+ getTemplateFromFullPathName(pathName) + ".xml";
            final InputStream is = null != data.getData() 
                        ? new ByteArrayInputStream(data.getData()) 
                        : null;
            
            try {
                final Properties props =  new Properties();
                if (null != is) {
                    props.load( is );
                }
                
                this._unitAdmin.createUnit(
                        pathName,
                        pattern,
                        Maps.fromProperties(props),
                        true);
            } finally {
                if ( null != is) {
                    is.close();
                }
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
                LOG.debug("update unit with {}", pathName);
            }
            final InputStream is = null != data.getData() 
                        ? new ByteArrayInputStream(data.getData()) 
                        : null;
            
            try {
                final Properties props =  new Properties();
                if (null != is) {
                    props.load( is );
                }
                
                this._unitAdmin.updateUnit(
                        pathName,
                        new HashMap<String, String>() {
                            private static final long serialVersionUID = 1L;
                        {
                            for ( Map.Entry<Object,Object> entry : props.entrySet() ) {
                                this.put(entry.getKey().toString(), entry.getValue().toString());
                            }
                        }});
            } finally {
                if ( null != is) {
                    is.close();
                }
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
        final String sourceName = parseSourceFromPath(root, data.getPath());
        if (null != sourceName) {
            if ( LOG.isDebugEnabled()) {
                LOG.debug("remove unit for {}", sourceName);
            }
            this._unitAdmin.deleteUnit(sourceName);
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
