package org.jocean.xharbor.reactor;

import org.jocean.http.CloseException;
import org.jocean.http.FullMessage;
import org.jocean.xharbor.api.TradeReactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import rx.Observable;
import rx.Single;

public class DropRequest implements TradeReactor {

    private static final Logger LOG = LoggerFactory
            .getLogger(DropRequest.class);

    public DropRequest(
            final MatchRule matcher,
            final boolean enableLog) {
        this._matcher = matcher;
        this._log = enableLog;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("DropRequest [matcher=").append(_matcher).append("]");
        return builder.toString();
    }

    @Override
    public Single<Boolean> match(final ReactContext ctx, final InOut io) {
        if (null != io.outbound()) {
            return Single.just(false);
        }
        return io.inbound().first().map(fullreq -> _matcher.match(fullreq.message())).toSingle();
    }

    @Override
    public Single<? extends InOut> react(final ReactContext ctx, final InOut io) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("try {} for trade {}", this, ctx.trade());
        }
        if (null != io.outbound()) {
            return Single.<InOut>just(null);
        }
        return io.inbound().first().map(fullreq -> {
            if (_matcher.match(fullreq.message())) {
                return io4Drop(ctx, io, fullreq);
            } else {
                // not handle this trade
                return null;
            }
        }).toSingle();
    }

    private InOut io4Drop(final ReactContext ctx, final InOut originalio,
            final FullMessage<HttpRequest> orgreq) {
        return new InOut() {
            @Override
            public Observable<FullMessage<HttpRequest>> inbound() {
                return originalio.inbound();
            }
            @Override
            public Observable<FullMessage<HttpResponse>> outbound() {
                return Observable.<FullMessage<HttpResponse>>error(new CloseException())
                    .doOnError(e -> {
                            if (e instanceof CloseException && _log) {
                                LOG.info("Drop request directly:\nREQ\n[{}]", orgreq.message());
                            }
                        });
            }};
    }

    private final MatchRule _matcher;
    private final boolean _log;
}
