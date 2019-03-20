package org.jocean.xharbor.reactor;

import static org.junit.Assert.assertEquals;

import org.jocean.http.FullMessage;
import org.jocean.http.MessageBody;
import org.jocean.xharbor.api.TradeReactor.InOut;
import org.junit.Test;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import rx.Observable;

public class BasicAuthenticateTestCase {

    @Test
    public final void testBasicAuthorizer() {
        final BasicAuthenticate authorizer = new BasicAuthenticate();

        authorizer._matcher = new MatchRule();
        authorizer._matcher.setPath("/needauth(\\w)*");

        authorizer._user = "hello";
        authorizer._password = "world";
        authorizer._strWWWAuthenticate = "demo";

        final InOut io = authorizer.react(TestReactorUtil.nullctx(), new InOut() {
                @Override
                public Observable<FullMessage<HttpRequest>> inbound() {
                    return Observable.just(new FullMessage<HttpRequest>() {
                        @Override
                        public HttpRequest message() {
                            return new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.POST, "/needauth/xxx");
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

        final HttpResponse response = io.outbound().toBlocking().single().message();

        assertEquals(HttpResponseStatus.UNAUTHORIZED, response.status());
        assertEquals(HttpVersion.HTTP_1_0, response.protocolVersion());
        assertEquals("Basic realm=\"demo\"", response.headers().get(HttpHeaderNames.WWW_AUTHENTICATE));
    }
}
