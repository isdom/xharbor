package org.jocean.xharbor.scheduler;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jocean.xharbor.api.HttpMessageTransformer;
import org.jocean.xharbor.api.SessionContext;
import org.jocean.xharbor.api.SessionScheduler;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import rx.Single;
import rx.SingleSubscriber;
import rx.functions.Action1;

public class RewritePathScheduler implements SessionScheduler {

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
                    public void call(HttpObject msg) {
                        if (!subscriber.isUnsubscribed()) {
                            if (msg instanceof HttpRequest) {
                                final Matcher matcher = _pathPattern.matcher(((HttpRequest)msg).getUri());
                                if ( matcher.find() ) {
                                    subscriber.onSuccess(new RewritePathTransformer(_pathPattern.pattern(), _replaceTo));
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
    
    private final Pattern _pathPattern;
    private final String _replaceTo;
}
