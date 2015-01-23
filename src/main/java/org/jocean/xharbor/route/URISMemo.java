/**
 * 
 */
package org.jocean.xharbor.route;

import java.net.URI;

/**
 * @author isdom
 *
 */
public interface URISMemo {
    public boolean isDown(final URI uri);
    public void markDownStatus(final URI uri, final boolean isDown);
}
