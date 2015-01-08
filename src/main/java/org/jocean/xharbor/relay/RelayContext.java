/**
 * 
 */
package org.jocean.xharbor.relay;

import java.net.URI;

import org.jocean.xharbor.util.BizMemo;

/**
 * @author isdom
 *
 */
public interface RelayContext {
    
    public enum STATE { 
        OBTAINING_HTTPCLIENT,
        TRANSFER_CONTENT,
        RECV_RESP,
        RELAY_SUCCESS,
        RELAY_FAILURE,
        SOURCE_CANCELED,
        CONNECTDESTINATION_FAILURE
    }
    
    public interface RelayMemo extends BizMemo<STATE> {
    }
    
    public URI relayTo();
    public RelayMemo memo();
}
