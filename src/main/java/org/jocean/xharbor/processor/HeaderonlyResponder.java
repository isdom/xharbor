package org.jocean.xharbor.processor;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.xharbor.api.HttpMessageTransformer;

import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import rx.Observable;
import rx.functions.Func0;
import rx.functions.Func1;

public class HeaderonlyResponder implements HttpMessageTransformer {

    public HeaderonlyResponder(
            final int responseStatus, 
            final Map<String, String> extraHeaders) {
        this._responseStatus = responseStatus;
        this._extraHeaders = extraHeaders;
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
                return Observable.<HttpObject>just(buildResponse(ref.get()));
            }});
    }

    private FullHttpResponse buildResponse(final HttpRequest request) {
        final FullHttpResponse response = new DefaultFullHttpResponse(
                null!=request ? request.getProtocolVersion() : HttpVersion.HTTP_1_1, 
                HttpResponseStatus.valueOf(_responseStatus));
        HttpHeaders.setHeader(response, HttpHeaders.Names.CONTENT_LENGTH, 0);
        if (null!=_extraHeaders) {
            for (Map.Entry<String, String> entry : _extraHeaders.entrySet()) {
                response.headers().set(entry.getKey(), entry.getValue());
            }
        }
        return response;
    }

    private final Func1<Throwable, Observable<HttpObject>> _FORWARD_ERROR = 
        new Func1<Throwable, Observable<HttpObject>>() {
            @Override
            public Observable<HttpObject> call(final Throwable e) {
                return Observable.error(e);
        }};
        
    private final int _responseStatus;
    private final Map<String, String> _extraHeaders;
}
