/**
 * 
 */
package org.jocean.xharbor.route;

import java.net.URI;

import org.jocean.xharbor.relay.RelayContext;
import org.jocean.xharbor.spi.Router;

/**
 * @author isdom
 *
 */
public class URIs2RelayCtxRouter implements Router<URI[], RelayContext> {

    public interface MemoFactory {
        public RelayContext.RelayMemo getRelayMemo(final String path, final URI relayTo);
    }

    public URIs2RelayCtxRouter(final MemoFactory memoFactory) {
        this._memoFactory = memoFactory;
    }
    
    @Override
    public RelayContext calculateRoute(final URI[] uris, final Context routectx) {
        if (uris != null && uris.length > 0) {
            final URI uri = uris[(int)(Math.random() * uris.length)];
            final RelayContext.RelayMemo memo = this._memoFactory.getRelayMemo((String)routectx.getProperty("path"), uri);
            return new RelayContext() {

                @Override
                public URI relayTo() {
                    return uri;
                }

                @Override
                public RelayMemo memo() {
                    return memo;
                }};
        }
        else {
            return null;
        }
    }
    
    private final MemoFactory _memoFactory;
}
