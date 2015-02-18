/**
 * 
 */
package org.jocean.xharbor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.jocean.xharbor.util.ZKUpdater.Operator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    public Object doAddOrUpdate(
            final Object ctx, 
            final String root, 
            final TreeCacheEvent event)
            throws Exception {
        final ChildData data = event.getData();
        final String sourceName = parseSourceFromPath(root, data.getPath());
        if ( null != sourceName ) {
            if ( LOG.isDebugEnabled()) {
                LOG.debug("create or update unit with {}", sourceName);
            }
            this._unitAdmin.deleteUnit(sourceName);
            
            final String pattern = "**"+ getTemplateFromSourceName(sourceName) + ".xml";
            final InputStream is = new ByteArrayInputStream(data.getData());
            
            try {
                final Properties props =  new Properties();
                props.load( is );
                
                this._unitAdmin.createUnit(
                        sourceName,
                        pattern,
                        new HashMap<String, String>() {
                            private static final long serialVersionUID = 1L;
                        {
                            for ( Map.Entry<Object,Object> entry : props.entrySet() ) {
                                this.put(entry.getKey().toString(), entry.getValue().toString());
                            }
                        }},
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

    private String getTemplateFromSourceName(final String sourceName) {
        String  template = sourceName;
        int idx = template.lastIndexOf(".");
        if ( -1 != idx ) {
            template = template.substring(idx + 1);
        }
        return template;
    }
    
    private final UnitAdmin _unitAdmin;
}
