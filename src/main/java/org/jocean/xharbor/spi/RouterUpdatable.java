/**
 * 
 */
package org.jocean.xharbor.spi;

/**
 * @author isdom
 *
 */
public interface RouterUpdatable<ROUTECTX, RELAYCTX> {
    public void updateRouter(final Router<ROUTECTX, RELAYCTX> router);
}
