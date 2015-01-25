/**
 * 
 */
package org.jocean.xharbor.util;

import java.net.URI;

/**
 * @author isdom
 *
 */
public interface ServiceMemo {
    public boolean isServiceDown(final URI uri);
    public void markServiceDownStatus(final URI uri, final boolean isDown);
}
