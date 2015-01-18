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
    
    public enum STEP { 
        ROUTING,
        OBTAINING_HTTPCLIENT,
        TRANSFER_CONTENT,
        RECV_RESP,
    }
    
    public enum RESULT { 
        NO_ROUTING,
        CONNECTDESTINATION_FAILURE,
        RELAY_SUCCESS,
        RELAY_FAILURE,
        SOURCE_CANCELED,
    }
    
    public interface RelayMemo extends BizMemo<STEP, RESULT> {
    }
    
    public URI relayTo();
    public RelayMemo memo();
}
