/**
 * 
 */
package org.jocean.httpgateway.impl;

import java.net.URI;

/**
 * @author isdom
 *
 */
interface RelayContext {
    
    public interface RelayMemo {
        public void incObtainingHttpClient();
        public void decObtainingHttpClient();
        
        public void incTransferContent();
        public void decTransferContent();
        
        public void incRecvResp();
        public void decRecvResp();
        
        public void incRelaySuccess();
        
        public void incSourceCanceled();
        public void incConnectDestinationFailure();
        public void incRelayFailure();
    }
    
    public URI relayTo();
    public RelayMemo memo();
}
