/**
 * 
 */
package org.jocean.xharbor.api;

import java.net.URI;

/**
 * @author isdom
 *
 */
public interface Target {
        
    public URI serviceUri();
    
    public void markServiceDownStatus(final boolean isDown);
    
    public int addWeight(final int deltaWeight);
    
    public void markAPIDownStatus(final boolean isDown);
}
