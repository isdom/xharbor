/**
 * 
 */
package org.jocean.xharbor.route;

import java.net.URI;

import org.jocean.xharbor.spi.Router;
import org.jocean.xharbor.util.URISMemo;

/**
 * @author isdom
 *
 */
public class SelectURI implements Router<TargetSet, URI> {

    public SelectURI(final URISMemo memo) {
        this._urisMemo = memo;
    }
    
    @Override
    public URI calculateRoute(final TargetSet targetSet, final Context context) {
        context.setProperty("targetSet", targetSet);
        context.setProperty("urisMemo", this._urisMemo);
        return targetSet.selectTarget(this._urisMemo);
    }
    
    private final URISMemo _urisMemo;
}
