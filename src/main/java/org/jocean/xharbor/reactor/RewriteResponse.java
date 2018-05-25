package org.jocean.xharbor.reactor;

import java.util.Map;

import org.jocean.http.util.RxNettys;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.xharbor.api.TradeReactor;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import rx.Observable;
import rx.Single;

public class RewriteResponse implements TradeReactor {

    public RewriteResponse(
            final MatchRule matcher,
            final Map<String, String> extraHeaders) {
        this._matcher = matcher;
        this._extraHeaders = extraHeaders;
    }

    @Override
    public Single<? extends InOut> react(final ReactContext ctx, final InOut io) {
        if (null == io.outbound()) {
            return Single.<InOut>just(null);
        }
        return io.inbound().map(DisposableWrapperUtil.unwrap()).compose(RxNettys.asHttpRequest())
                .map(req -> {
                        if (null == req) {
                            return null;
                        } else {
                            if (_matcher.match(req)) {
                                return io4rewriteResponse(io, req);
                            } else {
                                //  not handle this trade
                                return null;
                            }
                        }
                    })
                .toSingle();
    }

    private InOut io4rewriteResponse(final InOut originalio, final HttpRequest originalreq) {
        return new InOut() {
            @Override
            public Observable<? extends DisposableWrapper<HttpObject>> inbound() {
                return originalio.inbound();
            }

            @Override
            public Observable<? extends DisposableWrapper<HttpObject>> outbound() {
                return originalio.outbound().doOnNext(dwh -> {
                    if (dwh.unwrap() instanceof HttpResponse) {
                        final HttpResponse response = (HttpResponse) dwh.unwrap();
                        for (final Map.Entry<String, String> entry : _extraHeaders.entrySet()) {
                            response.headers().set(entry.getKey(), entry.getValue());
                        }
                    }
                });
            }
        };
    }

    private final MatchRule _matcher;
    private final Map<String, String> _extraHeaders;
}
