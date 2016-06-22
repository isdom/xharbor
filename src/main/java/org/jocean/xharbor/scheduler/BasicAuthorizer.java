package org.jocean.xharbor.scheduler;

import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.util.RxNettys;
import org.jocean.xharbor.api.HttpMessageTransformer;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import rx.Observable;
import rx.functions.Func0;
import rx.functions.Func1;

public class BasicAuthorizer implements HttpMessageTransformer {
    public BasicAuthorizer(
            final String strWWWAuthenticate) {
        this._strWWWAuthenticate = strWWWAuthenticate;
    }

    @Override
    public Observable<HttpObject> call(final Observable<HttpObject> source) {
        final AtomicReference<HttpRequest> ref = new AtomicReference<>();
        return source.flatMap(new Func1<HttpObject, Observable<HttpObject>>() {
            @Override
            public Observable<HttpObject> call(final HttpObject msg) {
                if (msg instanceof HttpRequest) {
                    ref.set((HttpRequest)msg);
                }
                return Observable.empty();
            }}, 
            _FORWARD_ERROR, 
            new Func0<Observable<HttpObject>>() {
            @Override
            public Observable<HttpObject> call() {
                return buildResponse(ref.get());
            }});
    }

    private Observable<HttpObject> buildResponse(final HttpRequest request) {
        return RxNettys.response401Unauthorized(null!=request ? request.getProtocolVersion() : HttpVersion.HTTP_1_1, 
                this._strWWWAuthenticate);
    }
    
    private final Func1<Throwable, Observable<HttpObject>> _FORWARD_ERROR = 
            new Func1<Throwable, Observable<HttpObject>>() {
                @Override
                public Observable<HttpObject> call(final Throwable e) {
                    return Observable.error(e);
            }};
            
    private final String _strWWWAuthenticate;
}
