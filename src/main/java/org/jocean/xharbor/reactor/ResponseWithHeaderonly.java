package org.jocean.xharbor.reactor;

import java.util.Map;

import org.jocean.http.HttpSlice;
import org.jocean.http.HttpSliceUtil;
import org.jocean.http.MessageUtil;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.xharbor.api.TradeReactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import rx.Observable;
import rx.Single;

public class ResponseWithHeaderonly implements TradeReactor {

    private static final Logger LOG = LoggerFactory
            .getLogger(ResponseWithHeaderonly.class);

    public ResponseWithHeaderonly(
            final MatchRule matcher,
            final int responseStatus,
            final Map<String, String> extraHeaders,
            final boolean enableLogReact) {
        this._matcher = matcher;
        this._responseStatus = responseStatus;
        this._extraHeaders = extraHeaders;
        this._logReact = enableLogReact;
    }

    @Override
    public Single<? extends InOut> react(final ReactContext ctx, final InOut io) {
        if (null != io.outbound()) {
            return Single.<InOut>just(null);
        }
        return io.inbound().first().compose(HttpSliceUtil.<HttpRequest>extractHttpMessage()).map(req -> {
            if (null == req) {
                LOG.warn("request is null, ignore trade {}", ctx.trade());
                return null;
            } else {
                if (this._matcher.match(req)) {
                    return io4Response(ctx, io, req);
                } else {
                    // not handle this trade
                    return null;
                }
            }
        }).toSingle();
    }

    private InOut io4Response(final ReactContext ctx, final InOut orgio, final HttpRequest orgreq) {
        return new InOut() {
            @Override
            public Observable<? extends HttpSlice> inbound() {
                return orgio.inbound();
            }
            @Override
            public Observable<? extends HttpSlice> outbound() {
                final HttpResponse response = new DefaultHttpResponse(
                        orgreq.protocolVersion(), HttpResponseStatus.valueOf(_responseStatus));
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
                if (null!=_extraHeaders) {
                    for (final Map.Entry<String, String> entry : _extraHeaders.entrySet()) {
                        response.headers().set(entry.getKey(), entry.getValue());
                    }
                }
                return HttpSliceUtil.single(Observable.just(response, LastHttpContent.EMPTY_LAST_CONTENT)
                    .map(DisposableWrapperUtil.wrap(RxNettys.disposerOf(), null != ctx ? ctx.trade() : null))
                    .doOnCompleted(() -> {
                        if (_logReact) {
                            LOG.info("RESPOND sendback response directly:\nREQ\n[{}]\nRESP\n[{}]", orgreq, response);
                        }
                    })).delay(any -> orgio.inbound().compose(MessageUtil.AUTOSTEP2DWH).last());
            }};
    }

    private final MatchRule _matcher;
    private final int _responseStatus;
    private final Map<String, String> _extraHeaders;
    private final boolean _logReact;
}
