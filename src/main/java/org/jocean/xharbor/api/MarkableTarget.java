/**
 * 
 */
package org.jocean.xharbor.api;


/**
 * @author isdom
 *
 */
public interface MarkableTarget extends Target {
        
    public void markServiceDownStatus(final boolean isDown);
    
    public int addWeight(final int deltaWeight);
    
    public void markAPIDownStatus(final boolean isDown);
}
