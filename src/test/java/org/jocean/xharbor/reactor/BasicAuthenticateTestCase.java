package org.jocean.xharbor.reactor;

import static org.junit.Assert.assertEquals;

import org.jocean.http.util.HttpMessageHolder;
import org.jocean.http.util.RxNettys;
import org.jocean.xharbor.api.TradeReactor.InOut;
import org.jocean.xharbor.reactor.BasicAuthenticate;
import org.junit.Test;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import rx.Observable;

public class BasicAuthenticateTestCase {

    @Test
    public final void testBasicAuthorizer() {
        final BasicAuthenticate authorizer = new BasicAuthenticate(
                new MatchRule(null, "/needauth(\\w)*", null), 
                "hello", "world", "demo");
        
        final DefaultFullHttpRequest orgreq = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.POST, 
                "/needauth/xxx");
        
        final InOut io = 
            authorizer.react(null, new InOut() {
                @Override
                public Observable<? extends HttpObject> inbound() {
                    return Observable.<HttpObject>just(orgreq);
                }
                @Override
                public Observable<? extends HttpObject> outbound() {
                    return null;
                }})
            .toBlocking().value();
        
        final HttpMessageHolder holder = new HttpMessageHolder();
        io.outbound().compose(holder.<HttpObject>assembleAndHold()).subscribe();
        
        final FullHttpResponse response = holder.fullOf(RxNettys.BUILD_FULL_RESPONSE).call();
        
        assertEquals(HttpResponseStatus.UNAUTHORIZED, response.status());
        assertEquals(HttpVersion.HTTP_1_0, response.protocolVersion());
        assertEquals("demo", response.headers().get(HttpHeaderNames.WWW_AUTHENTICATE));
    }
}
