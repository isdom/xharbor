/**
 * 
 */
package org.jocean.xharbor.relay.impl;

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
        
        public void ttl4ObtainingHttpClient(final long ttl);
        public void ttl4TransferContent(final long ttl);
        public void ttl4RecvResp(final long ttl);
        
        public void ttl4RelaySuccess(final long ttl);
        public void ttl4SourceCanceled(final long ttl);
        public void ttl4ConnectDestinationFailure(final long ttl);
        public void ttl4RelayFailure(final long ttl);
    }
    
    public URI relayTo();
    public RelayMemo memo();
}
