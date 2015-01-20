/**
 * 
 */
package org.jocean.xharbor;

import java.lang.reflect.Field;

import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class DirectMemoryIndicator {
    private static final Logger LOG = 
            LoggerFactory.getLogger(DirectMemoryIndicator.class);
    
    public DirectMemoryIndicator() {
        try {
            final Class<?> c = Class.forName("java.nio.Bits");
            _maxMemory = c.getDeclaredField("maxMemory");
            _maxMemory.setAccessible(true);
            _reservedMemory = c.getDeclaredField("reservedMemory");
            _reservedMemory.setAccessible(true);
        } catch (Exception e) {
            LOG.warn("exception when init DirectMemoryIndicator, detail:{}", 
                    ExceptionUtils.exception2detail(e));
        }
    }
    
    public long getMaxMemoryInBytes() throws Exception {
        return null != this._maxMemory 
                ? this._maxMemory.getLong(null) : 0;
    }
    
    public long getReservedMemoryInBytes() throws Exception {
        return null != this._reservedMemory 
                ? this._reservedMemory.getLong(null) : 0;
    }
    
    private Field _maxMemory;
    private Field _reservedMemory;
}
