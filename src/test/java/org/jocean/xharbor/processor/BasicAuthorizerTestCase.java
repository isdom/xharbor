package org.jocean.xharbor.processor;

import static org.junit.Assert.*;

import org.jocean.http.util.RxNettys;
import org.junit.Test;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import rx.Observable;
import rx.observables.ConnectableObservable;
import rx.observers.TestSubscriber;

public class BasicAuthorizerTestCase {

    @Test
    public final void testBasicAuthorizer() {
        final BasicAuthorizer authorizer = new BasicAuthorizer("demo");
        
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.POST, 
                "/yjy_psm/fetchMetadata");
        
        final TestSubscriber<HttpObject> respSubscriber = new TestSubscriber<>();
        
        final ConnectableObservable<HttpObject> reqObservable = 
                Observable.<HttpObject>just(request).publish();
        
        reqObservable.flatMap(RxNettys.splitFullHttpMessage())
            .compose(authorizer)
            .flatMap(RxNettys.splitFullHttpMessage())
            .subscribe(respSubscriber);
        
        respSubscriber.assertNoValues();
        reqObservable.connect();
        
        respSubscriber.assertValueCount(2);
        assertEquals(HttpResponseStatus.UNAUTHORIZED, 
                ((HttpResponse)respSubscriber.getOnNextEvents().get(0)).getStatus());
        assertEquals(HttpVersion.HTTP_1_0, 
                ((HttpResponse)respSubscriber.getOnNextEvents().get(0)).getProtocolVersion());
    }

    @Test
    public final void testBasicAuthorizer2() {
        final BasicAuthorizer authorizer = new BasicAuthorizer("demo");
        
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, 
                "/yjy_psm/fetchMetadata");
        
        final TestSubscriber<HttpObject> respSubscriber = new TestSubscriber<>();
        
        final ConnectableObservable<HttpObject> reqObservable = 
                Observable.<HttpObject>just(request).publish();
        
        reqObservable.flatMap(RxNettys.splitFullHttpMessage())
            .compose(authorizer)
            .flatMap(RxNettys.splitFullHttpMessage())
            .subscribe(respSubscriber);
        
        respSubscriber.assertNoValues();
        reqObservable.connect();
        
        respSubscriber.assertValueCount(2);
        assertEquals(HttpResponseStatus.UNAUTHORIZED, 
                ((HttpResponse)respSubscriber.getOnNextEvents().get(0)).getStatus());
        assertEquals(HttpVersion.HTTP_1_1, 
                ((HttpResponse)respSubscriber.getOnNextEvents().get(0)).getProtocolVersion());
    }
}
