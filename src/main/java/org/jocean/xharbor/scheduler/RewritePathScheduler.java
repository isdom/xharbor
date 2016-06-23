package org.jocean.xharbor.scheduler;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jocean.xharbor.api.HttpMessageTransformer;
import org.jocean.xharbor.api.SessionContext;
import org.jocean.xharbor.api.SessionScheduler;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import rx.Observable;
import rx.Single;
import rx.SingleSubscriber;
import rx.functions.Action1;
import rx.functions.Func1;

public class RewritePathScheduler implements SessionScheduler, HttpMessageTransformer {

    public RewritePathScheduler(
            final String pathPattern, 
            final String replaceTo) {
        this._pathPattern = safeCompilePattern(pathPattern);
        this._replaceTo = replaceTo;
    }
    
    private static Pattern safeCompilePattern(final String regex) {
        return null != regex && !"".equals(regex) ? Pattern.compile(regex) : null;
    }
    
    @Override
    public Single<? extends HttpMessageTransformer> schedule(
            final SessionContext ctx) {
        return Single.create(new Single.OnSubscribe<HttpMessageTransformer>() {
            @Override
            public void call(
                    final SingleSubscriber<? super HttpMessageTransformer> subscriber) {
                ctx.inbound().first().subscribe(new Action1<HttpObject>() {
                    @Override
                    public void call(final HttpObject msg) {
                        if (!subscriber.isUnsubscribed()) {
                            if (msg instanceof HttpRequest) {
                                final Matcher matcher = _pathPattern.matcher(((HttpRequest)msg).getUri());
                                if ( matcher.find() ) {
                                    subscriber.onSuccess(RewritePathScheduler.this);
                                } else {
                                    subscriber.onSuccess(null);
                                }
                            } else {
                                subscriber.onSuccess(null);
                            }
                        }
                    }},
                    new Action1<Throwable>() {
                        @Override
                        public void call(Throwable e) {
                            if (!subscriber.isUnsubscribed()) {
                                subscriber.onError(e);
                            }
                        }}
                    );
            }});
    }
    
    @Override
    public Observable<HttpObject> call(final Observable<HttpObject> source) {
        return source.map(this._replacer);
    }
    
    private final Pattern _pathPattern;
    private final String _replaceTo;
    private final Func1<HttpObject, HttpObject> _replacer = new Func1<HttpObject, HttpObject>() {

        @Override
        public HttpObject call(final HttpObject msg) {
            if (msg instanceof HttpRequest) {
                final HttpRequest orgreq = (HttpRequest)msg;
                final DefaultHttpRequest newreq = new DefaultHttpRequest(orgreq.getProtocolVersion(), 
                        orgreq.getMethod(), orgreq.getUri(), true);
                newreq.headers().set(orgreq.headers());
                
                return newreq.setUri(
                    _pathPattern.matcher(orgreq.getUri())
                        .replaceFirst(_replaceTo));
            } else {
                return msg;
            }
        }};
}
