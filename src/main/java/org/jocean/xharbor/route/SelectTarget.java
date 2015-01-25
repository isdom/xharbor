/**
 * 
 */
package org.jocean.xharbor.route;

import org.jocean.xharbor.spi.Router;
import org.jocean.xharbor.util.ServiceMemo;

/**
 * @author isdom
 *
 */
public class SelectTarget implements Router<TargetSet, Target> {

    public SelectTarget(final ServiceMemo memo) {
        this._serviceMemo = memo;
    }
    
    @Override
    public Target calculateRoute(final TargetSet targetSet, final Context context) {
        return targetSet.selectTarget(this._serviceMemo);
    }
    
    private final ServiceMemo _serviceMemo;
}
