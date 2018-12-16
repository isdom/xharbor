package org.jocean.xharbor.api;

import java.util.Iterator;

import org.jocean.http.FullMessage;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.idiom.StopWatch;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import rx.Observable;
import rx.Single;
import rx.SingleSubscriber;
import rx.functions.Action1;

public interface TradeReactor {
    public interface ReactContext {
        public HttpTrade trade();
        public StopWatch watch();
    }

    public interface InOut {
        public Observable<FullMessage<HttpRequest>> inbound();
        public Observable<FullMessage<HttpResponse>> outbound();
    }

    public Single<? extends InOut> react(final ReactContext ctx, final InOut io);

    public static class OP {
        public static Single<? extends InOut> first(final Iterable<? extends TradeReactor> Iterable,
                final ReactContext ctx, final InOut io) {
            return Single.create(new Single.OnSubscribe<InOut>() {
                @Override
                public void call(final SingleSubscriber<? super InOut> subscriber) {
                    reactByFirst(ctx, io, Iterable.iterator(), subscriber);
                }});
        }

        public static Single<? extends InOut> all(final Iterable<? extends TradeReactor> Iterable,
                final ReactContext ctx, final InOut io) {
            return Single.create(new Single.OnSubscribe<InOut>() {
                @Override
                public void call(final SingleSubscriber<? super InOut> subscriber) {
                    reactAll(ctx, io, Iterable.iterator(), subscriber, false);
                }});
        }

        private static void reactByFirst(final ReactContext ctx, final InOut io,
                final Iterator<? extends TradeReactor> iter,
                final SingleSubscriber<? super InOut> subscriber) {
            if (!subscriber.isUnsubscribed()) {
                if (iter.hasNext()) {
                    final TradeReactor reactor = iter.next();
                    reactor.react(ctx, io).subscribe(new Action1<InOut>() {
                        @Override
                        public void call(final InOut newio) {
                            if (!subscriber.isUnsubscribed()) {
                                if (null != newio) {
                                    subscriber.onSuccess(newio);
                                } else {
                                    reactByFirst(ctx, io, iter, subscriber);
                                }
                            }
                        }}, new Action1<Throwable>() {
                            @Override
                            public void call(final Throwable error) {
                                if (!subscriber.isUnsubscribed()) {
                                    subscriber.onError(error);
                                }
                            }});
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
                    reactor.react(ctx, io).subscribe(new Action1<InOut>() {
                        @Override
                        public void call(final InOut newio) {
                            if (!subscriber.isUnsubscribed()) {
                                if (null != newio) {
                                    //  trade handled
                                    reactAll(ctx, newio, iter, subscriber, true);
                                } else {
                                    reactAll(ctx, io, iter, subscriber, handled);
                                }
                            }
                        }}, new Action1<Throwable>() {
                            @Override
                            public void call(final Throwable error) {
                                if (!subscriber.isUnsubscribed()) {
                                    subscriber.onError(error);
                                }
                            }});
                } else {
                    subscriber.onSuccess(handled ? io : null);
                }
            }
        }
    }
}
