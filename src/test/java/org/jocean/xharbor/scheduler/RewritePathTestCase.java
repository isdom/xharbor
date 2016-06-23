package org.jocean.xharbor.scheduler;

import static org.junit.Assert.assertEquals;

import org.jocean.http.util.RxNettys;
import org.jocean.xharbor.api.HttpMessageTransformer;
import org.jocean.xharbor.api.SessionContext;
import org.jocean.xharbor.scheduler.RewritePathTransformer;
import org.junit.Test;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import rx.Observable;
import rx.functions.Func1;
import rx.observers.TestSubscriber;

public class RewritePathTestCase {

    @Test
    public final void testRewritePathTransformerSuccess() {
        final RewritePathTransformer transformer = 
                new RewritePathTransformer("/yjy_psm/fetchMetadata", "/yjy_common/fetchMetadata");
        
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/yjy_psm/fetchMetadata");
        
        final TestSubscriber<HttpObject> reqSubscriber = new TestSubscriber<>();
        
        Observable.<HttpObject>just(request)
            .flatMap(RxNettys.splitFullHttpMessage())
            .compose(transformer)
            .subscribe(reqSubscriber);
        
        reqSubscriber.assertValueCount(2);
        assertEquals("/yjy_common/fetchMetadata", 
                ((HttpRequest)reqSubscriber.getOnNextEvents().get(0)).getUri());
        assertEquals("/yjy_psm/fetchMetadata", request.getUri());
    }

    @Test
    public final void testRewritePathSchedulerSuccess() {
        final RewritePathScheduler scheduler = 
                new RewritePathScheduler("/yjy_psm/fetchMetadata", "/yjy_common/fetchMetadata");
        
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/yjy_psm/fetchMetadata");
        
        final TestSubscriber<HttpObject> reqSubscriber = new TestSubscriber<>();
        final Observable<HttpObject> reqs = Observable.<HttpObject>just(request).flatMap(RxNettys.splitFullHttpMessage());
        
        scheduler.schedule(new SessionContext() {
            @Override
            public Observable<HttpObject> inbound() {
                return reqs;
            }
            @Override
            public Observable<HttpObject> outbound() {
                return null;
            }})
        .flatMapObservable(new Func1<HttpMessageTransformer, Observable<HttpObject>>() {
            @Override
            public Observable<HttpObject> call(final HttpMessageTransformer trans) {
                return reqs.compose(trans);
            }})
        .subscribe(reqSubscriber);
        
        reqSubscriber.assertValueCount(2);
        assertEquals("/yjy_common/fetchMetadata", 
                ((HttpRequest)reqSubscriber.getOnNextEvents().get(0)).getUri());
        assertEquals("/yjy_psm/fetchMetadata", request.getUri());
    }
}
