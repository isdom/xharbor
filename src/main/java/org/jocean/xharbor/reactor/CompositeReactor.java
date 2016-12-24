package org.jocean.xharbor.reactor;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicStampedReference;

import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.idiom.Ordered;
import org.jocean.xharbor.api.TradeReactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Single;

public class CompositeReactor implements TradeReactor, Ordered {
    
    private static final TradeReactor[] EMPTY_REACTOR = new TradeReactor[0];
    private static final Comparator<TradeReactor> ORDER_REACTOR_DESC = new Comparator<TradeReactor>() {
        @Override
        public int compare(final TradeReactor o1, final TradeReactor o2) {
            if ((o1 instanceof Ordered) && (o2 instanceof Ordered)) {
                return ((Ordered)o2).ordinal() - ((Ordered)o1).ordinal();
            } else if (o1 instanceof Ordered) {
                // o2 is not ordered
                return -1;
            } else if (o2 instanceof Ordered) {
                // o1 is not ordered
                return 1;
            } else {
                // either o1 nor o2 is ordered
                return o1.hashCode() - o2.hashCode();
            }
        }};
    
    private static final Logger LOG = LoggerFactory
            .getLogger(CompositeReactor.class);
    
    public void setOrdinal(final int ordinal) {
        this._ordinal = ordinal;
    }
    
    public void addReactor(final TradeReactor reactor) {
        this._reactors.add(reactor);
        updateStampAndRule();
    }
    
    public void removeReactor(final TradeReactor reactor) {
        this._reactors.remove(reactor);
        updateStampAndRule();
    }
    
    private void updateStampAndRule() {
        final int newStamp = this._stampProvider.incrementAndGet();
        
        while (this._descReactorsRef.getStamp() < newStamp) {
            this._descReactorsRef.attemptStamp(this._descReactorsRef.getReference(), newStamp);
        }
        
        if (this._descReactorsRef.getStamp() == newStamp) {
            // now this stamp is the newest
            final TradeReactor[] newReactors = this._reactors.toArray(EMPTY_REACTOR);
            Arrays.sort(newReactors, ORDER_REACTOR_DESC);
            if (this._descReactorsRef.compareAndSet(this._descReactorsRef.getReference(), newReactors, 
                    newStamp, newStamp)) {
                LOG.info("CompositeReactor's rule has update to stamp({}) success.", newStamp);
            } else {
                LOG.info("CompositeReactor's rule try update to stamp({}) failed, bcs other newest stamp({}) exist.",
                        newStamp, this._descReactorsRef.getStamp());
            }
        } else {
            LOG.info("CompositeReactor's rule try update to stamp({}) failed, bcs other newest stamp({}) exist.",
                    newStamp, this._descReactorsRef.getStamp());
        }
    }

    @Override
    public Single<? extends InOut> react(final HttpTrade trade, final InOut io) {
        final TradeReactor[] reactors = this._descReactorsRef.getReference();
        if (null == reactors ||
            (null != reactors && reactors.length == 0)) {
            return Single.<InOut>just(null);
        } else {
            return TradeReactor.OP.all(Arrays.asList(reactors), trade, io);
        }
    }

    @Override
    public int ordinal() {
        return this._ordinal;
    }
    
    private final AtomicInteger _stampProvider = new AtomicInteger(0);
    
    private final List<TradeReactor> _reactors = 
            new CopyOnWriteArrayList<>();
    
    private final AtomicStampedReference<TradeReactor[]> _descReactorsRef = 
            new AtomicStampedReference<>(null, 0);
    
    private int _ordinal = 0;
}
