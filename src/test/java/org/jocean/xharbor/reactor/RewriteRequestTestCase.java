package org.jocean.xharbor.reactor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;

import org.jocean.http.ByteBufSlice;
import org.jocean.http.FullMessage;
import org.jocean.http.MessageBody;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.xharbor.api.TradeReactor;
import org.jocean.xharbor.api.TradeReactor.InOut;
import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import rx.Observable;
import rx.functions.Action1;

public class RewriteRequestTestCase {

    public static final byte[] CONTENT = { 'H', 'e', 'l', 'l', 'o', ' ', 'W', 'o', 'r', 'l', 'd' };

    @Test
    public final void testRewritePathSuccess() {
        final TradeReactor reactor =
                new RewriteRequest("/yjy_psm/fetchMetadata", "/yjy_common/fetchMetadata", null, null);

        final HttpRequest orgreq = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/yjy_psm/fetchMetadata");

        final InOut io = reactor.react(null, new InOut() {
                @Override
                public Observable<FullMessage<HttpRequest>> inbound() {
                    return Observable.just(new FullMessage<HttpRequest>() {
                        @Override
                        public HttpRequest message() {
                            return orgreq;
                        }
                        @Override
                        public Observable<? extends MessageBody> body() {
                            return Observable.empty();
                        }});
                }
                @Override
                public Observable<FullMessage<HttpResponse>> outbound() {
                    return null;
                }})
            .toBlocking().value();

        final HttpRequest rwreq = io.inbound().toBlocking().single().message();

        assertEquals("/yjy_psm/fetchMetadata", orgreq.uri());
        assertEquals("/yjy_common/fetchMetadata", rwreq.uri());
    }

    @Test
    public final void testNoNeedRewritePath() {
        final TradeReactor reactor =
                new RewriteRequest("/yjy_psm/fetchMetadata", "/yjy_common/fetchMetadata", null, null);

        final HttpRequest orgreq = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/noNeedRewrite");

        final InOut io =
            reactor.react(null, new InOut() {
                @Override
                public Observable<FullMessage<HttpRequest>> inbound() {
                    return Observable.just(new FullMessage<HttpRequest>() {
                        @Override
                        public HttpRequest message() {
                            return orgreq;
                        }
                        @Override
                        public Observable<? extends MessageBody> body() {
                            return Observable.empty();
                        }});
                }
                @Override
                public Observable<FullMessage<HttpResponse>> outbound() {
                    return null;
                }})
            .toBlocking().value();

        assertNull(io);
    }

    @Test
    public final void testRewritePathAndKeepRequestBody() throws IOException {
        final TradeReactor reactor =
                new RewriteRequest("/yjy_psm/fetchMetadata", "/yjy_common/fetchMetadata", null, null);

        final HttpRequest orgreq = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/yjy_psm/fetchMetadata");

        final InOut io = reactor.react(null, new InOut() {
                @Override
                public Observable<FullMessage<HttpRequest>> inbound() {
                    return Observable.just(new FullMessage<HttpRequest>() {
                        @Override
                        public HttpRequest message() {
                            return orgreq;
                        }
                        @Override
                        public Observable<? extends MessageBody> body() {
                            return Observable.just(new MessageBody() {
                                @Override
                                public String contentType() {
                                    return "text/plain";
                                }

                                @Override
                                public int contentLength() {
                                    return CONTENT.length;
                                }

                                @Override
                                public Observable<? extends ByteBufSlice> content() {
                                    return Observable.just(new ByteBufSlice() {
                                        @Override
                                        public void step() {}

                                        @Override
                                        public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                                            return Observable.just(DisposableWrapperUtil.wrap(Unpooled.wrappedBuffer(CONTENT), (Action1<ByteBuf>)null))
                                                    .toList().toBlocking().single();
                                        }});
                                }});
                        }});
                }
                @Override
                public Observable<FullMessage<HttpResponse>> outbound() {
                    return null;
                }})
            .toBlocking().value();

        final FullHttpRequest rwreq = io.inbound().compose(RxNettys.fullmessage2dwq(null, true)).toBlocking().single().unwrap();

        assertEquals("/yjy_psm/fetchMetadata", orgreq.uri());
        assertEquals("/yjy_common/fetchMetadata", rwreq.uri());

        assertTrue(Arrays.equals(Nettys.dumpByteBufAsBytes(rwreq.content()), CONTENT));
    }
}
