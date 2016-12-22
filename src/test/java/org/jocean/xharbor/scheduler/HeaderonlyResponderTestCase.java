package org.jocean.xharbor.scheduler;

import static org.junit.Assert.assertEquals;

import org.jocean.http.util.RxNettys;
import org.jocean.xharbor.scheduler.HeaderonlyResponder;
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

public class HeaderonlyResponderTestCase {

    @Test
    public final void testHeaderonlyResponder() {
        final HeaderonlyResponder responder = new HeaderonlyResponder(200, null);
        
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.POST, 
                "/yjy_psm/fetchMetadata");
        
        final TestSubscriber<HttpObject> respSubscriber = new TestSubscriber<>();
        
        final ConnectableObservable<HttpObject> reqObservable = 
                Observable.<HttpObject>just(request).publish();
        
        reqObservable.flatMap(RxNettys.splitFullHttpMessage())
            .compose(responder)
            .flatMap(RxNettys.splitFullHttpMessage())
            .subscribe(respSubscriber);
        
        respSubscriber.assertNoValues();
        reqObservable.connect();
        
        respSubscriber.assertValueCount(2);
        assertEquals(HttpResponseStatus.OK, 
                ((HttpResponse)respSubscriber.getOnNextEvents().get(0)).status());
        assertEquals(HttpVersion.HTTP_1_0, 
                ((HttpResponse)respSubscriber.getOnNextEvents().get(0)).protocolVersion());
    }

    @Test
    public final void testHeaderonlyResponder2() {
        final HeaderonlyResponder responder = new HeaderonlyResponder(200, null);
        
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, 
                "/yjy_psm/fetchMetadata");
        
        final TestSubscriber<HttpObject> respSubscriber = new TestSubscriber<>();
        
        final ConnectableObservable<HttpObject> reqObservable = 
                Observable.<HttpObject>just(request).publish();
        
        reqObservable.flatMap(RxNettys.splitFullHttpMessage())
            .compose(responder)
            .flatMap(RxNettys.splitFullHttpMessage())
            .subscribe(respSubscriber);
        
        respSubscriber.assertNoValues();
        reqObservable.connect();
        
        respSubscriber.assertValueCount(2);
        assertEquals(HttpResponseStatus.OK, 
                ((HttpResponse)respSubscriber.getOnNextEvents().get(0)).status());
        assertEquals(HttpVersion.HTTP_1_1, 
                ((HttpResponse)respSubscriber.getOnNextEvents().get(0)).protocolVersion());
    }
}
