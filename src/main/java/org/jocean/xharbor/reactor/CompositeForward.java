package org.jocean.xharbor.reactor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicStampedReference;

import javax.inject.Inject;

import org.jocean.idiom.BeanFinder;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Ordered;
import org.jocean.xharbor.api.RelayMemo;
import org.jocean.xharbor.api.ServiceMemo;
import org.jocean.xharbor.api.TradeReactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;

import io.netty.util.Timer;
import rx.Single;
import rx.SingleSubscriber;
import rx.functions.Action0;

public class CompositeForward implements TradeReactor, Ordered {

    private static final ForwardTrade[] EMPTY_FORWARD = new ForwardTrade[0];
    private static final ForwardData[] EMPTY_DATA = new ForwardData[0];

    private static final Logger LOG = LoggerFactory
            .getLogger(CompositeForward.class);

    @Override
    public String toString() {
        final int maxLen = 10;
        final StringBuilder builder = new StringBuilder();
        builder.append("CompositeForward [forwards=")
                .append(_forwards != null ? _forwards.subList(0, Math.min(_forwards.size(), maxLen)) : null)
                .append(", ordinal=").append(_ordinal).append("]");
        return builder.toString();
    }

    public void setOrdinal(final int ordinal) {
        this._ordinal = ordinal;
    }

    @Override
    public int ordinal() {
        return this._ordinal;
    }

    public Action0 addForward(final ForwardData data) {
        this._forwards.add(data);
        updateStampAndRule();
        return () -> removeForward(data);
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
            for (final ForwardData f : data) {
                ForwardTrade reactor = matcher2reactor.get(f.matcher());
                if (null == reactor) {
                    reactor = new ForwardTrade(f.serviceName(),
                            f.matcher(),
                            this._finder,
                            this._memoBuilder,
                            this._serviceMemo,
                            this._timer);
                    matcher2reactor.put(f.matcher(), reactor);
                }
                reactor.addTarget(f.target());
            }
            final ForwardTrade[] newReactors = matcher2reactor.values().toArray(EMPTY_FORWARD);
            if (this._reactorsRef.compareAndSet(this._reactorsRef.getReference(), newReactors, newStamp, newStamp)) {
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
    public Single<Boolean> match(final ReactContext ctx, final InOut io) {
        return Single.just(true);
    }

    @Override
    public Single<? extends InOut> react(final ReactContext ctx, final InOut io) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("try {} for trade {}", this, ctx.trade());
        }
        final ForwardTrade[] reactors = this._reactorsRef.getReference();
        if (null == reactors || (null != reactors && reactors.length == 0)) {
            return Single.<InOut>just(null);
        } else {
//            return TradeReactor.OP.first(Arrays.asList(reactors), ctx, io);
            return first(reactors, ctx, io, ctx.concurrent());
        }
    }

    public static <T extends TradeReactor> Single<? extends InOut> first(
            final T[] reactors,
            final ReactContext ctx,
            final InOut io,
            final int concurrent) {
        return Single.create(subscriber -> reactByFirst(ctx, io, reactors, 0, concurrent, subscriber));
    }

    private static <T extends TradeReactor> void reactByFirst(
            final ReactContext ctx,
            final InOut io,
            final T[] reactors,
            final int start,
            final int concurrent,
            final SingleSubscriber<? super InOut> subscriber) {
        if (!subscriber.isUnsubscribed()) {
            final List<Single<Boolean>> domatchs = new ArrayList<>();
            final int count = Math.min(reactors.length - start, concurrent);
            for (int idx = start; idx < start + count; idx++) {
                domatchs.add(reactors[idx].match(ctx, io).subscribeOn(ctx.scheduler()));
            }
            Single.<Integer>zip(domatchs, results -> {
                    int idx = 0;
                    for (final Object ismatch : results) {
                        if ((Boolean)ismatch) {
                            return idx;
                        }
                        idx++;
                    }
                    return -1;
                }).subscribe(idx -> {
                    if (-1 == idx) {
                        // no matched forward, so next
                        if (start + count >= reactors.length) {
                            // end of reactors
                            subscriber.onSuccess(null);
                        } else {
                            reactByFirst(ctx, io, reactors, start + count, concurrent, subscriber);
                        }
                    } else {
                        // matched
                        reactors[start + idx].react(ctx, io).subscribe(subscriber);
                    }
                }, e -> {
                    LOG.trace("invoke onError {} for {}", ExceptionUtils.exception2detail(e), ctx.trade());
                    if (!subscriber.isUnsubscribed()) {
                        subscriber.onError(e);
                    }
                });
        }
    }

    private final AtomicInteger _stampProvider = new AtomicInteger(0);

    private final List<ForwardData> _forwards = new CopyOnWriteArrayList<>();

    private final AtomicStampedReference<ForwardTrade[]> _reactorsRef = new AtomicStampedReference<>(null, 0);

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
