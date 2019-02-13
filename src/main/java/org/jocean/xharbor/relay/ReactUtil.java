package org.jocean.xharbor.relay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.jocean.idiom.ExceptionUtils;
import org.jocean.xharbor.api.TradeReactor;
import org.jocean.xharbor.api.TradeReactor.InOut;
import org.jocean.xharbor.api.TradeReactor.ReactContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Single;
import rx.SingleSubscriber;
import rx.functions.Func3;

public class ReactUtil {
        private static final Logger LOG = LoggerFactory.getLogger(ReactUtil.class);

        public static Func3<TradeReactor[],ReactContext,InOut,Single<? extends InOut>> reactAll() {
            return (reactors, ctx, io) -> all(Arrays.asList(reactors), ctx, io);
        }

        public static Single<? extends InOut> all(
                final Iterable<? extends TradeReactor> iterable,
                final ReactContext ctx,
                final InOut io) {
            return Single.create(subscriber -> reactAll(ctx, io, iterable.iterator(), subscriber, false));
        }

        private static void reactAll(final ReactContext ctx, final InOut io,
                final Iterator<? extends TradeReactor> iter,
                final SingleSubscriber<? super InOut> subscriber,
                final boolean handled) {
            if (!subscriber.isUnsubscribed()) {
                if (iter.hasNext()) {
                    final TradeReactor reactor = iter.next();
                    LOG.trace("before {} react for {}", reactor, ctx.trade());
                    reactor.react(ctx, io).subscribe(newio -> {
                            LOG.trace("after {} react for {}", reactor, ctx.trade());
                            if (!subscriber.isUnsubscribed()) {
                                if (null != newio) {
                                    //  trade handled
                                    LOG.trace("invoke reactAll with newio {} for {}", newio, ctx.trade());
                                    reactAll(ctx, newio, iter, subscriber, true);
                                } else {
                                    LOG.trace("invoke reactAll with orgio {} for {}", io, ctx.trade());
                                    reactAll(ctx, io, iter, subscriber, handled);
                                }
                            }
                        },  e -> {
                            LOG.trace("invoke onError {} for {}", ExceptionUtils.exception2detail(e), ctx.trade());
                            if (!subscriber.isUnsubscribed()) {
                                subscriber.onError(e);
                            }
                        });
                } else {
                    subscriber.onSuccess(handled ? io : null);
                }
            }
        }

        public static Func3<TradeReactor[],ReactContext,InOut,Single<? extends InOut>> reactFirst() {
            return (reactors, ctx, io) -> first(Arrays.asList(reactors), ctx, io);
        }

        public static Single<? extends InOut> first(final Iterable<? extends TradeReactor> iterable,
                final ReactContext ctx, final InOut io) {
            return Single.create(subscriber -> reactByFirst(ctx, io, iterable.iterator(), subscriber));
        }

        private static void reactByFirst(
                final ReactContext ctx, final InOut io,
                final Iterator<? extends TradeReactor> iter,
                final SingleSubscriber<? super InOut> subscriber) {
            if (!subscriber.isUnsubscribed()) {
                if (iter.hasNext()) {
                    final TradeReactor reactor = iter.next();
                    LOG.trace("before {} react for {}", reactor, ctx.trade());
                    reactor.react(ctx, io).subscribe(newio -> {
                        LOG.trace("after {} react for {}", reactor, ctx.trade());
                        if (!subscriber.isUnsubscribed()) {
                            if (null != newio) {
                                LOG.trace("invoke onSuccess with newio {} for {}", newio, ctx.trade());
                                subscriber.onSuccess(newio);
                            } else {
                                LOG.trace("invoke reactByFirst with orgio {} for {}", io, ctx.trade());
                                reactByFirst(ctx, io, iter, subscriber);
                            }
                        }
                    },  e -> {
                        LOG.trace("invoke onError {} for {}", ExceptionUtils.exception2detail(e), ctx.trade());
                        if (!subscriber.isUnsubscribed()) {
                            subscriber.onError(e);
                        }
                    });
                } else {
                    subscriber.onSuccess(null);
                }
            }
        }

        public static Func3<TradeReactor[],ReactContext,InOut,Single<? extends InOut>> parallelFirstof() {
            return (reactors, ctx, io) -> Single.create(subscriber -> reactByFirst(ctx, io, reactors, 0, ctx.concurrent(), subscriber));
        }

        private static void reactByFirst(
                final ReactContext ctx,
                final InOut io,
                final TradeReactor[] reactors,
                final int start,
                final int concurrent,
                final SingleSubscriber<? super InOut> subscriber) {
            if (!subscriber.isUnsubscribed()) {
                final int count = Math.min(reactors.length - start, concurrent);
                matchsOf(ctx, io, reactors, start, count).subscribe(idx -> {
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

        private static Single<Integer> matchsOf(
                final ReactContext ctx,
                final InOut io,
                final TradeReactor[] reactors,
                final int start,
                final int count) {
            if (count > 1) {
                LOG.debug("concurrent count is {}, try parallel", count);
                final List<Single<Boolean>> domatchs = new ArrayList<>();
                for (int idx = start; idx < start + count; idx++) {
                    domatchs.add(reactors[idx].match(ctx, io).subscribeOn(ctx.scheduler()));
                }
                return Single.<Integer>zip(domatchs, results -> {
                        int idx = 0;
                        for (final Object ismatch : results) {
                            if ((Boolean)ismatch) {
                                return idx;
                            }
                            idx++;
                        }
                        return -1;
                    });
            } else {
                LOG.debug("concurrent count is {}, back to serial", count);
                return reactors[start].match(ctx, io).map(matched -> matched ? 0 : -1);
            }
        }
}
