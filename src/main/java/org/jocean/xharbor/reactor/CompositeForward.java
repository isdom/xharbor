package org.jocean.xharbor.reactor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicStampedReference;

import javax.inject.Inject;

import org.jocean.http.client.HttpClient;
import org.jocean.idiom.Ordered;
import org.jocean.xharbor.api.RelayMemo;
import org.jocean.xharbor.api.ServiceMemo;
import org.jocean.xharbor.api.TradeReactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;

import io.netty.util.Timer;
import rx.Single;
import rx.functions.Action0;
import rx.functions.Func1;

public class CompositeForward implements TradeReactor, Ordered, Func1<ForwardData, Action0> {
    
    private static final ForwardTrade[] EMPTY_FORWARD = new ForwardTrade[0];
    private static final ForwardData[] EMPTY_DATA = new ForwardData[0];
    
    private static final Logger LOG = LoggerFactory
            .getLogger(CompositeForward.class);
    
    @Override
    public Action0 call(final ForwardData data) {
        addForward(data);
        return new Action0() {
            @Override
            public void call() {
                removeForward(data);
            }};
    }
    
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
            final Map<MatchRule, ForwardTrade> matcher2reactor = Maps.newHashMap();
            for (ForwardData f : data) {
                ForwardTrade reactor = matcher2reactor.get(f.matcher());
                if (null == reactor) {
                    reactor = new ForwardTrade(f.matcher(), this._httpclient, this._memoBuilder, this._serviceMemo, this._timer);
                    matcher2reactor.put(f.matcher(), reactor);
                }
                reactor.addTarget(f.target());
            }
            final ForwardTrade[] newReactors = matcher2reactor.values().toArray(EMPTY_FORWARD);
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
    public Single<? extends InOut> react(final ReactContext ctx, final InOut io) {
        final ForwardTrade[] reactors = this._reactorsRef.getReference();
        if (null == reactors ||
            (null != reactors && reactors.length == 0)) {
            return Single.<InOut>just(null);
        } else {
            return TradeReactor.OP.first(Arrays.asList(reactors), ctx, io);
        }
    }

    private final AtomicInteger _stampProvider = new AtomicInteger(0);
    
    private final List<ForwardData> _forwards = 
            new CopyOnWriteArrayList<>();
    
    private final AtomicStampedReference<ForwardTrade[]> _reactorsRef = 
            new AtomicStampedReference<>(null, 0);
    
    @Inject
    private HttpClient _httpclient;
    
    @Inject
    private RelayMemo.Builder _memoBuilder;
    
    @Inject
    private ServiceMemo     _serviceMemo;
    
    @Inject
    private Timer _timer;
    
    private int _ordinal = 0;
}
