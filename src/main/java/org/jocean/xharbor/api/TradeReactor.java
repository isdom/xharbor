package org.jocean.xharbor.api;

import java.util.Iterator;

import org.jocean.http.server.HttpServerBuilder.HttpTrade;

import io.netty.handler.codec.http.HttpObject;
import rx.Observable;
import rx.Single;
import rx.SingleSubscriber;
import rx.functions.Action1;

public interface TradeReactor {
    public interface InOut {
        public Observable<? extends HttpObject> inbound();
        public Observable<? extends HttpObject> outbound();
    }
    
    public Single<? extends InOut> react(final HttpTrade trade, final InOut io);
    
    public static class OP {
        public static Single<? extends InOut> first(final Iterable<? extends TradeReactor> Iterable,
                final HttpTrade trade, final InOut io) {
            return Single.create(new Single.OnSubscribe<InOut>() {
                @Override
                public void call(final SingleSubscriber<? super InOut> subscriber) {
                    reactByFirst(trade, io, Iterable.iterator(), subscriber);
                }});
        }
        private static void reactByFirst(final HttpTrade trade, final InOut io,
                final Iterator<? extends TradeReactor> iter,
                final SingleSubscriber<? super InOut> subscriber) {
            if (!subscriber.isUnsubscribed()) {
                if (iter.hasNext()) {
                    final TradeReactor reactor = iter.next();
                    reactor.react(trade, io).subscribe(new Action1<InOut>() {
                        @Override
                        public void call(final InOut newio) {
                            if (!subscriber.isUnsubscribed()) {
                                if (null != newio) {
                                    subscriber.onSuccess(newio);
                                } else {
                                    reactByFirst(trade, io, iter, subscriber);
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
    }
}
