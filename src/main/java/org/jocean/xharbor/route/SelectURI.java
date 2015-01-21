/**
 * 
 */
package org.jocean.xharbor.route;

import java.net.URI;

import org.jocean.xharbor.spi.Router;

/**
 * @author isdom
 *
 */
public class SelectURI implements Router<TargetSet, URI> {

    @Override
    public URI calculateRoute(final TargetSet targetSet, final Context context) {
        context.setProperty("targetSet", targetSet);
        return targetSet.selectTarget();
    }
}
