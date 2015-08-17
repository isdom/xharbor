/**
 * 
 */
package org.jocean.xharbor.api;

import java.net.URI;

import org.jocean.http.Feature;

import rx.functions.Func0;

/**
 * @author isdom
 *
 */
public interface Target {
        
    public URI serviceUri();
    
    public Func0<Feature[]> features();
}
