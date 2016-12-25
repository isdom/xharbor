package org.jocean.xharbor.reactor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicStampedReference;

import javax.inject.Inject;

import org.jocean.http.client.HttpClient;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.idiom.Ordered;
import org.jocean.xharbor.api.TradeReactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;

import rx.Single;

public class CompositeForward implements TradeReactor, Ordered {
    
    private static final TradeForward[] EMPTY_FORWARD = new TradeForward[0];
    private static final ForwardData[] EMPTY_DATA = new ForwardData[0];
    
    private static final Logger LOG = LoggerFactory
            .getLogger(CompositeForward.class);
    
    public void setOrdinal(final int ordinal) {
        this._ordinal = ordinal;
    }
    
    @Override
    public int ordinal() {
        return this._ordinal;
    }
    
    public void addForward(final ForwardData data) {
        this._forwards.add(data);
        updateStampAndRule();
    }
    
    public void removeForward(final ForwardData data) {
        this._forwards.remove(data);
        updateStampAndRule();
    }
    
    private void updateStampAndRule() {
        final int newStamp = this._stampProvider.incrementAndGet();
        
        while (this._reactorsRef.getStamp() < newStamp) {
            this._reactorsRef.attemptStamp(this._reactorsRef.getReference(), newStamp);
        }
        
        if (this._reactorsRef.getStamp() == newStamp) {
            // now this stamp is the newest
            final ForwardData[] data = this._forwards.toArray(EMPTY_DATA);
            final Map<MatchRule, TradeForward> rule2reactor = Maps.newHashMap();
            for (ForwardData f : data) {
                TradeForward reactor = rule2reactor.get(f.rule());
                if (null == reactor) {
                    reactor = new TradeForward(this._httpclient, f.rule());
                    rule2reactor.put(f.rule(), reactor);
                }
                reactor.addTarget(f.target());
            }
            final TradeForward[] newReactors = rule2reactor.values().toArray(EMPTY_FORWARD);
            if (this._reactorsRef.compareAndSet(this._reactorsRef.getReference(), newReactors, 
                    newStamp, newStamp)) {
                LOG.info("CompositeForward's rule has update to stamp({}) success.", newStamp);
            } else {
                LOG.info("CompositeForward's rule try update to stamp({}) failed, bcs other newest stamp({}) exist.",
                        newStamp, this._reactorsRef.getStamp());
            }
        } else {
            LOG.info("CompositeForward's rule try update to stamp({}) failed, bcs other newest stamp({}) exist.",
                    newStamp, this._reactorsRef.getStamp());
        }
    }

    @Override
    public Single<? extends InOut> react(final HttpTrade trade, final InOut io) {
        final TradeForward[] reactors = this._reactorsRef.getReference();
        if (null == reactors ||
            (null != reactors && reactors.length == 0)) {
            return Single.<InOut>just(null);
        } else {
            return TradeReactor.OP.first(Arrays.asList(reactors), trade, io);
        }
    }

    private final AtomicInteger _stampProvider = new AtomicInteger(0);
    
    private final List<ForwardData> _forwards = 
            new CopyOnWriteArrayList<>();
    
    private final AtomicStampedReference<TradeForward[]> _reactorsRef = 
            new AtomicStampedReference<>(null, 0);
    
    @Inject
    private HttpClient _httpclient;
    
    private int _ordinal = 0;
}
