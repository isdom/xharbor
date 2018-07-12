package org.jocean.xharbor.reactor;

import static org.junit.Assert.assertEquals;

import org.jocean.http.HttpSlice;
import org.jocean.http.HttpSliceUtil;
import org.jocean.http.MessageUtil;
import org.jocean.http.util.RxNettys;
import org.jocean.xharbor.api.TradeReactor.InOut;
import org.junit.Test;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
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
                public Observable<? extends HttpSlice> inbound() {
                    return HttpSliceUtil.single(Observable.just(RxNettys.wrap4release(request)));
                }
                @Override
                public Observable<? extends HttpSlice> outbound() {
                    return null;
                }})
            .toBlocking().value();

        final FullHttpResponse response = io.outbound()
                .compose(MessageUtil.rollout2dwhs())
                .compose(RxNettys.message2fullresp(null))
                .toBlocking().single().unwrap();

        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(HttpVersion.HTTP_1_0, response.protocolVersion());
    }
}
