package org.jocean.xharbor.reactor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;

import org.jocean.http.util.HttpMessageHolder;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.jocean.xharbor.api.TradeReactor;
import org.jocean.xharbor.api.TradeReactor.InOut;
import org.jocean.xharbor.reactor.RewriteRequest;
import org.junit.Test;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpVersion;
import rx.Observable;

public class RewriteRequestTestCase {

    public static final byte[] CONTENT = { 'H', 'e', 'l', 'l', 'o', ' ', 'W', 'o', 'r', 'l', 'd' };
    
    @Test
    public final void testRewritePathSuccess() {
        final TradeReactor reactor = 
                new RewriteRequest("/yjy_psm/fetchMetadata", "/yjy_common/fetchMetadata", null, null);
        
        final DefaultFullHttpRequest orgreq = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/yjy_psm/fetchMetadata");
        
        final InOut io = 
            reactor.react(null, new InOut() {
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
        io.inbound().compose(holder.assembleAndHold()).subscribe();
        
        final FullHttpRequest rwreq = holder.httpMessageBuilder(RxNettys.BUILD_FULL_REQUEST).call();
        
        assertEquals("/yjy_psm/fetchMetadata", orgreq.uri());
        assertEquals("/yjy_common/fetchMetadata", rwreq.uri());
    }

    @Test
    public final void testNoNeedRewritePath() {
        final TradeReactor reactor = 
                new RewriteRequest("/yjy_psm/fetchMetadata", "/yjy_common/fetchMetadata", null, null);
        
        final DefaultFullHttpRequest orgreq = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/noNeedRewrite");
        
        final InOut io = 
            reactor.react(null, new InOut() {
                @Override
                public Observable<? extends HttpObject> inbound() {
                    return Observable.<HttpObject>just(orgreq);
                }
                @Override
                public Observable<? extends HttpObject> outbound() {
                    return null;
                }})
            .toBlocking().value();
        
        assertNull(io);
    }

    @Test
    public final void testRewritePathAndKeepRequestBody() throws IOException {
        final TradeReactor reactor = 
                new RewriteRequest("/yjy_psm/fetchMetadata", "/yjy_common/fetchMetadata", null, null);
        
        final DefaultFullHttpRequest orgreq = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/yjy_psm/fetchMetadata",
                        Unpooled.wrappedBuffer(CONTENT));
        
        final InOut io = 
            reactor.react(null, new InOut() {
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
        io.inbound().compose(holder.assembleAndHold()).subscribe();
        
        final FullHttpRequest rwreq = holder.httpMessageBuilder(RxNettys.BUILD_FULL_REQUEST).call();
        
        assertEquals("/yjy_psm/fetchMetadata", orgreq.uri());
        assertEquals("/yjy_common/fetchMetadata", rwreq.uri());
        
        assertTrue(Arrays.equals(Nettys.dumpByteBufAsBytes(rwreq.content()), CONTENT));
    }
}
