package org.jocean.xharbor.reactor;

import static org.junit.Assert.assertEquals;

import org.jocean.http.FullMessage;
import org.jocean.http.MessageBody;
import org.jocean.xharbor.api.TradeReactor.InOut;
import org.junit.Test;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import rx.Observable;

public class ResponseWithHeaderonlyTestCase {

    @Test
    public final void testHeaderonlyResponder() {
        final ResponseWithHeaderonly responder = new ResponseWithHeaderonly();

        responder._matcher = new MatchRule();
        responder._matcher.setPath("/yourname/(\\w)*");


        final InOut io = responder.react(TestReactorUtil.nullctx(), new InOut() {
                @Override
                public Observable<FullMessage<HttpRequest>> inbound() {
                    return Observable.just(new FullMessage<HttpRequest>() {
                        @Override
                        public HttpRequest message() {
                            return new DefaultHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.POST, "/yourname/hi");
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

        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(HttpVersion.HTTP_1_0, response.protocolVersion());
    }
}
