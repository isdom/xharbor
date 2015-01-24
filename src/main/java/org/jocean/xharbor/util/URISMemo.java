/**
 * 
 */
package org.jocean.xharbor.util;

import java.net.URI;

/**
 * @author isdom
 *
 */
public interface URISMemo {
    public boolean isDown(final URI uri);
    public void markDownStatus(final URI uri, final boolean isDown);
}
