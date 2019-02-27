package org.jocean.xharbor.reactor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicStampedReference;

import javax.inject.Inject;

import org.jocean.idiom.BeanFinder;
import org.jocean.idiom.Ordered;
import org.jocean.xharbor.api.RelayMemo;
import org.jocean.xharbor.api.ServiceMemo;
import org.jocean.xharbor.api.TradeReactor;
import org.jocean.xharbor.relay.ReactUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;

import io.netty.util.Timer;
import rx.Single;
import rx.functions.Action0;

public class CompositeForward implements TradeReactor, Ordered {

    private static final ForwardTrade[] EMPTY_FWDT = new ForwardTrade[0];
    private static final ForwardData[] EMPTY_FWDD = new ForwardData[0];

    private static final Logger LOG = LoggerFactory
            .getLogger(CompositeForward.class);

    @Override
    public String toString() {
        final int maxLen = 10;
        final StringBuilder builder = new StringBuilder();
        builder.append("CompositeForward [forwards=")
                .append(_fwdds != null ? _fwdds.subList(0, Math.min(_fwdds.size(), maxLen)) : null)
                .append(", ordinal=").append(_ordinal).append("]");
        return builder.toString();
    }

    @Override
    public String[] reactItems() {
        final TradeReactor[] reactors = _fwdtsRef.getReference();
        if (null != reactors) {
            final List<String> items = new ArrayList<>();
            for (final TradeReactor reactor : reactors) {
                items.addAll(Arrays.asList(reactor.reactItems()));
            }
            return items.toArray(new String[0]);
        } else {
            return new String[]{"CompositeForward: (empty)"};
        }
    }

    public void setOrdinal(final int ordinal) {
        this._ordinal = ordinal;
    }

    @Override
    public int ordinal() {
        return this._ordinal;
    }

    public Action0 addForward(final ForwardData data) {
        this._fwdds.add(data);
        updateStampAndRule();
        return () -> removeForward(data);
    }

    void removeForward(final ForwardData data) {
        this._fwdds.remove(data);
        updateStampAndRule();
    }

    private void updateStampAndRule() {
        final int newStamp = this._stampProvider.incrementAndGet();

        while (this._fwdtsRef.getStamp() < newStamp) {
            this._fwdtsRef.attemptStamp(this._fwdtsRef.getReference(), newStamp);
        }

        if (this._fwdtsRef.getStamp() == newStamp) {
            // now this stamp is the newest
            final ForwardData[] data = this._fwdds.toArray(EMPTY_FWDD);
            final Map<MatchRule, ForwardTrade> matcher2reactor = Maps.newHashMap();
            for (final ForwardData fwdd : data) {
                ForwardTrade fwdt = matcher2reactor.get(fwdd.matcher());
                if (null == fwdt) {
                    fwdt = new ForwardTrade(fwdd.serviceName(),
                            fwdd.matcher(),
                            this._finder,
                            this._memoBuilder,
                            this._serviceMemo,
                            this._timer);
                    matcher2reactor.put(fwdd.matcher(), fwdt);
                }
                fwdt.addTarget(fwdd.target());
            }
            final ForwardTrade[] newReactors = matcher2reactor.values().toArray(EMPTY_FWDT);
            if (this._fwdtsRef.compareAndSet(this._fwdtsRef.getReference(), newReactors, newStamp, newStamp)) {
                LOG.info("CompositeForward's rule has update to stamp({}) success.", newStamp);
            } else {
                LOG.info("CompositeForward's rule try update to stamp({}) failed, bcs other newest stamp({}) exist.",
                        newStamp, this._fwdtsRef.getStamp());
            }
        } else {
            LOG.info("CompositeForward's rule try update to stamp({}) failed, bcs other newest stamp({}) exist.",
                    newStamp, this._fwdtsRef.getStamp());
        }
    }

    @Override
    public Single<Boolean> match(final ReactContext ctx, final InOut io) {
        if (null != io.outbound()) {
            return Single.just(false);
        } else {
            return Single.just(true);
        }
    }

    @Override
    public Single<? extends InOut> react(final ReactContext ctx, final InOut io) {
        // skip when io has outbound
        if (null != io.outbound()) {
            return Single.<InOut>just(null);
        }
        if (LOG.isTraceEnabled()) {
            LOG.trace("try {} for trade {}", this, ctx.trade());
        }
        final ForwardTrade[] fwdts = this._fwdtsRef.getReference();
        if (null == fwdts || (null != fwdts && fwdts.length == 0)) {
            return Single.<InOut>just(null);
        } else {
            return ReactUtil.parallelFirst(fwdts, ctx, io);
        }
    }

    private final AtomicInteger _stampProvider = new AtomicInteger(0);

    private final List<ForwardData> _fwdds = new CopyOnWriteArrayList<>();

    private final AtomicStampedReference<ForwardTrade[]> _fwdtsRef = new AtomicStampedReference<>(null, 0);

    @Inject
    private BeanFinder _finder;

    @Inject
    private RelayMemo.Builder _memoBuilder;

    @Inject
    private ServiceMemo     _serviceMemo;

    @Inject
    private Timer _timer;

    private int _ordinal = 0;
}
