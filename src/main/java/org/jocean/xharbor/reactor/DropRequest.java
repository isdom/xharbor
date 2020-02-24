package org.jocean.xharbor.reactor;

import javax.inject.Inject;

import org.jocean.http.CloseException;
import org.jocean.http.FullMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import rx.Observable;
import rx.Single;

public class DropRequest extends SingleReactor {

    private static final Logger LOG = LoggerFactory.getLogger(DropRequest.class);

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("DropRequest [matcher=").append(_matcher)
            .append(",blockip=").append(_peerip)
            .append("]");
        return builder.toString();
    }

    @Override
    public Single<Boolean> match(final ReactContext ctx, final InOut io) {
        if (null != io.outbound()) {
            return Single.just(false);
        }
        return io.inbound().first().map(fullreq -> isMatched(fullreq)).toSingle();
    }

    public static String get1stIp(final String peerips) {
        return peerips.split(",")[0];
    }

    private boolean isMatched(final FullMessage<HttpRequest> fullreq) {
        if (null != this._peerip) {
            final String ips = fullreq.message().headers().get("x-forwarded-for");
            if (null != ips && get1stIp(ips).equals(this._peerip)) {
                return true;
            }
        }
        return _matcher.match(fullreq.message());
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
            if (isMatched(fullreq)) {
                return io4drop(ctx, io, fullreq);
            } else {
                // not handle this trade
                return null;
            }
        }).toSingle();
    }

    private InOut io4drop(final ReactContext ctx, final InOut originalio,
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

    @Inject
    MatchRule _matcher;

    @Value("${block.ip}")
    String _peerip = null;

    @Value("${log}")
    boolean _log = true;
}
