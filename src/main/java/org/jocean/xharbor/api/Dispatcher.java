/**
 * 
 */
package org.jocean.xharbor.api;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import rx.Observable;
import rx.functions.Action1;

/**
 * @author isdom
 *
 */
public interface Dispatcher {
    
    public class ResponseCtx {
        public Action1<RelayMemo.RESULT>  resultSetter;
        public Object  transport;
        
        public ResponseCtx(final Object transport) {
            this.transport = transport;
        }
    }
    
    public boolean IsValid();
    
    public Observable<HttpObject> response(
            final ResponseCtx ctx,
            final RoutingInfo info,
            final HttpRequest request, 
            final Observable<HttpObject> fullRequest);
}
