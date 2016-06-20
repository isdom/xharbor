package org.jocean.xharbor.processor;

import static org.junit.Assert.assertEquals;

import org.jocean.http.util.RxNettys;
import org.junit.Test;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import rx.Observable;
import rx.observers.TestSubscriber;

public class RewritePathTransformerTestCase {

    @Test
    public final void testRewritePathSuccess() {
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

}
