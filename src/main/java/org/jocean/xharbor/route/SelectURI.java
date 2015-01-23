/**
 * 
 */
package org.jocean.xharbor.route;

import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jocean.idiom.Function;
import org.jocean.idiom.SimpleCache;
import org.jocean.xharbor.spi.Router;

/**
 * @author isdom
 *
 */
public class SelectURI implements Router<TargetSet, URI> {

    public SelectURI(final Timer timer) {
        this._timer = timer;
    }
    
    @Override
    public URI calculateRoute(final TargetSet targetSet, final Context context) {
        context.setProperty("targetSet", targetSet);
        context.setProperty("urisMemo", this._urisMemo);
        return targetSet.selectTarget(this._urisMemo);
    }
    
    
    private class MemoImpl implements URISMemo {

        @Override
        public boolean isDown(final URI uri) {
            return this._statusCache.get(uri).get();
        }

        @Override
        public void markDownStatus(final URI uri, final boolean isDown) {
            this._statusCache.get(uri).set(isDown);
            if ( isDown) {
                _timer.newTimeout(new TimerTask() {
                    @Override
                    public void run(final Timeout timeout) throws Exception {
                        //  reset down flag
                        markDownStatus(uri, false);
                    }}, 60, TimeUnit.SECONDS);
            }
        }
        
        private final SimpleCache<URI, AtomicBoolean> _statusCache = 
                new SimpleCache<URI, AtomicBoolean>(new Function<URI, AtomicBoolean>() {
                    @Override
                    public AtomicBoolean apply(final URI input) {
                        return new AtomicBoolean(false);
                    }});
    }
    
    private final URISMemo _urisMemo = new MemoImpl();
    private final Timer _timer;
}
