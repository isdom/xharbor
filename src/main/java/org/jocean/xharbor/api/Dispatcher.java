/**
 * 
 */
package org.jocean.xharbor.api;

import org.jocean.http.server.CachedRequest;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import rx.Observable;

/**
 * @author isdom
 *
 */
public interface Dispatcher {
    
    public Target dispatch();
    
    public boolean IsValid();
    
    public Observable<? extends HttpObject> response(
            final HttpRequest request, 
            final CachedRequest cached);
}
