package org.jocean.xharbor.reactor;

import static org.junit.Assert.assertEquals;

import org.jocean.http.util.RxNettys;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.xharbor.api.TradeReactor.InOut;
import org.junit.Test;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import rx.Observable;

public class ResponseWithHeaderonlyTestCase {

    @Test
    public final void testHeaderonlyResponder() {
        final ResponseWithHeaderonly responder = new ResponseWithHeaderonly(
                new MatchRule(null, "/yourname/(\\w)*", null), 
                200, null, true);
        
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.POST, 
                "/yourname/hi");
        
        final InOut io = 
            responder.react(null, new InOut() {
                @Override
                public Observable<? extends DisposableWrapper<HttpObject>> inbound() {
                    return Observable.just(RxNettys.wrap4release(request));
                }
                @Override
                public Observable<? extends DisposableWrapper<HttpObject>> outbound() {
                    return null;
                }})
            .toBlocking().value();
        
        final FullHttpResponse response = io.outbound().compose(RxNettys.message2fullresp(null)).toBlocking().single().unwrap();
        
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(HttpVersion.HTTP_1_0, response.protocolVersion());
    }
}
