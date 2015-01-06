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
public class SelectURIRouter implements Router<URI[], URI> {

    @Override
    public URI calculateRoute(final URI[] uris, final Context context) {
        return (uris != null && uris.length > 0) ? uris[(int)(Math.random() * uris.length)] : null;
    }
}
