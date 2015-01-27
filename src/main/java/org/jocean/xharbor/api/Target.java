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
    
    public int addWeight(final int deltaWeight);
    
    public void markServiceDownStatus(final boolean isDown);
    
    public void markAPIDownStatus(final boolean isDown);
}
