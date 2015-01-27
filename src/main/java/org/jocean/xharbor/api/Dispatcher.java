/**
 * 
 */
package org.jocean.xharbor.api;

/**
 * @author isdom
 *
 */
public interface Dispatcher {
    public Target dispatch();
    public boolean IsValid();
}
