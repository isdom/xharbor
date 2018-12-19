package org.jocean.xharbor.api;

import java.util.Iterator;

import org.jocean.http.FullMessage;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.StopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.opentracing.Span;
import rx.Observable;
import rx.Single;
import rx.SingleSubscriber;

public interface TradeReactor {
    public interface ReactContext {
        public HttpTrade trade();
        public StopWatch watch();
        public Span span();
    }

    public interface InOut {
        public Observable<FullMessage<HttpRequest>> inbound();
        public Observable<FullMessage<HttpResponse>> outbound();
    }

    public Single<? extends InOut> react(final ReactContext ctx, final InOut io);

    public static class OP {
        private static final Logger LOG = LoggerFactory.getLogger(TradeReactor.class);
        public static Single<? extends InOut> first(final Iterable<? extends TradeReactor> Iterable,
                final ReactContext ctx, final InOut io) {
            return Single.create(subscriber -> reactByFirst(ctx, io, Iterable.iterator(), subscriber));
        }

        public static Single<? extends InOut> all(final Iterable<? extends TradeReactor> Iterable,
                final ReactContext ctx, final InOut io) {
            return Single.create(subscriber -> reactAll(ctx, io, Iterable.iterator(), subscriber, false));
        }

        private static void reactByFirst(final ReactContext ctx, final InOut io,
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
    }
}
