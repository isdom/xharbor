/**
 * 
 */
package org.jocean.xharbor.util;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.xharbor.api.Dispatcher;
import org.jocean.xharbor.api.ServiceMemo;
import org.jocean.xharbor.api.Target;

/**
 * @author isdom
 *
 */
public class TargetSet implements Dispatcher {

    private static final int MAX_EFFECTIVEWEIGHT = 1000;
    
    public TargetSet(final URI[] uris, final ServiceMemo serviceMemo) {
        this._serviceMemo = serviceMemo;
        this._targets = new ArrayList<TargetImpl>() {
            private static final long serialVersionUID = 1L;
        {
            for ( URI uri : uris) {
                this.add(new TargetImpl(uri));
            }
        }}.toArray(new TargetImpl[0]);
    }
    
    @Override
    public String toString() {
        return Arrays.toString( new ArrayList<String>() {
            private static final long serialVersionUID = 1L;
        {
            for (TargetImpl peer : _targets) {
                this.add(peer._uri.toString() + ":down(" + isTargetDown(peer)
                        + "):effectiveWeight(" + peer._effectiveWeight.get()
                        + "):currentWeight(" + peer._currentWeight.get()
                        + ")"
                        );
            }
        }}.toArray(new String[0]) );
    }

    @Override
    public Target dispatch() {
        int total = 0;
        TargetImpl best = null;
        for ( TargetImpl peer : this._targets ) {
            if ( !isTargetDown(peer) ) {
                // peer->current_weight += peer->effective_weight; 
                final int effectiveWeight = peer._effectiveWeight.get();
                final int currentWeight = peer._currentWeight.addAndGet( effectiveWeight );
                total += effectiveWeight;
                
//                if (best == NULL || peer->current_weight > best->current_weight) {
//                    best = peer;
//                }
                if ( null == best || best._currentWeight.get() < currentWeight ) {
                    best = peer;
                }
            }
        }
        
        if (null == best) {
            return null;
        }
        
//        best->current_weight -= total;
        best._currentWeight.addAndGet(-total);
        
        return best;
    }
    
    @Override
    public boolean IsValid() {
        return this._targets.length > 0;
    }

    /**
     * @param peer
     * @return
     */
    private boolean isTargetDown(final TargetImpl peer) {
        return this._serviceMemo.isServiceDown(peer._uri) || peer._down.get();
    }
    
    private class TargetImpl implements Target {
        @Override
        public URI serviceUri() {
            return this._uri;
        }
        
        @Override
        public int addWeight(final int deltaWeight) {
            int weight = this._effectiveWeight.addAndGet(deltaWeight);
            if ( weight > MAX_EFFECTIVEWEIGHT ) {
                weight = this._effectiveWeight.addAndGet(-deltaWeight);
            }
            return weight;
        }
        
        @Override
        public void markAPIDownStatus(final boolean isDown) {
            this._down.set(isDown);
        }
        
        @Override
        public void markServiceDownStatus(final boolean isDown) {
            _serviceMemo.markServiceDownStatus(this._uri, isDown);
        }
        
        TargetImpl(final URI uri) {
            this._uri = uri;
        }
        
        public final URI _uri;
        private final AtomicInteger _currentWeight = new AtomicInteger(1);
        private final AtomicInteger _effectiveWeight = new AtomicInteger(1);
        private final AtomicBoolean _down = new AtomicBoolean(false);
    }
    
    private final ServiceMemo _serviceMemo;
    private final TargetImpl[] _targets;
}
