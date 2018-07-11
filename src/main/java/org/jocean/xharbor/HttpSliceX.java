package org.jocean.xharbor;

import org.jocean.http.DoFlush;
import org.jocean.http.HttpSlice;
import org.jocean.idiom.DisposableWrapper;

import io.netty.handler.codec.http.HttpObject;
import rx.Completable;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.subscriptions.CompositeSubscription;
import rx.subscriptions.Subscriptions;

public class HttpSliceX {
    static final Func1<HttpSlice, Observable<DisposableWrapper<? extends HttpObject>>> elementAndSucceed = slice -> Observable
            .concat(slice.element(), slice.next().flatMap(next -> HttpSliceX.elementAndSucceed.call(next)));

    public static interface AwaredFlush extends DoFlush {
        public void onCompleted();
    }

    public static Action1<? super Object> handleAwaredFlush() {
        return obj -> {
            if (obj instanceof AwaredFlush) {
                ((AwaredFlush) obj).onCompleted();
            }
        };
    }

    public static interface CompletableSource<E> {
        public Observable<? extends E> element();
        public Completable completable();
    }

    public static class FlushCompletable implements CompletableSource<Object> {
        @Override
        public Completable completable() {
            return Completable.create(subscriber ->
                this._completed.add(Subscriptions.create(() -> subscriber.onCompleted())));
        }

        @Override
        public Observable<? extends Object> element() {
            return Observable.just(new AwaredFlush() {
                @Override
                public void onCompleted() {
                    _completed.unsubscribe();
                }});
        }

        private final CompositeSubscription _completed = new CompositeSubscription();
    }

    public static Observable<? extends Object> advanceByCompleted(final HttpSlice slice,
            final Func1<HttpSlice, CompletableSource<Object>> slice2completable) {
        final CompletableSource<Object> completableSource = slice2completable.call(slice);
        return Observable.concat(slice.element(), completableSource.element(), completableSource.completable()
                .andThen(slice.next().flatMap(next -> advanceByCompleted(next, slice2completable))));
    }

    public static Transformer<HttpSlice, Object> advanceBySended() {
        return slices -> slices.flatMap(slice -> advanceByCompleted(slice, any -> new FlushCompletable()));
    }
}
