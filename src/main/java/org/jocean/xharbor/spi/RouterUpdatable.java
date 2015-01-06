/**
 * 
 */
package org.jocean.xharbor.spi;

/**
 * @author isdom
 *
 */
public interface RouterUpdatable<INPUT, OUTPUT> {
    public void updateRouter(final Router<INPUT, OUTPUT> router);
}
