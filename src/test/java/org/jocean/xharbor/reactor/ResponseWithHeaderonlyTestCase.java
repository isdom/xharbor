package org.jocean.xharbor.reactor;

import static org.junit.Assert.assertEquals;

import org.jocean.http.util.HttpMessageHolder;
import org.jocean.http.util.RxNettys;
import org.jocean.xharbor.api.TradeReactor.InOut;
import org.jocean.xharbor.reactor.ResponseWithHeaderonly;
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
                public Observable<? extends HttpObject> inbound() {
                    return Observable.<HttpObject>just(request);
                }
                @Override
                public Observable<? extends HttpObject> outbound() {
                    return null;
                }})
            .toBlocking().value();
        
        final HttpMessageHolder holder = new HttpMessageHolder();
        io.outbound().compose(holder.<HttpObject>assembleAndHold()).subscribe();
        
        final FullHttpResponse response = holder.fullOf(RxNettys.BUILD_FULL_RESPONSE).call();
        
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(HttpVersion.HTTP_1_0, response.protocolVersion());
    }
}
