package org.jocean.xharbor.reactor;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import org.jocean.http.FullMessage;
import org.jocean.http.MessageBody;
import org.jocean.idiom.StepableUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import rx.Observable;
import rx.Single;

public class ResponseWithHeaderonly extends SingleReactor {

    private static final Logger LOG = LoggerFactory.getLogger(ResponseWithHeaderonly.class);

    @Override
    public String toString() {
        final int maxLen = 10;
        final StringBuilder builder = new StringBuilder();
        builder.append("ResponseWithHeaderonly [matcher=").append(_matcher).append(", responseStatus=")
                .append(_responseStatus).append(", extraHeaders=")
                .append(_extraHeaders != null ? toString(_extraHeaders.entrySet(), maxLen) : null).append("]");
        return builder.toString();
    }

    private String toString(final Collection<?> collection, final int maxLen) {
        final StringBuilder builder = new StringBuilder();
        builder.append("[");
        int i = 0;
        for (final Iterator<?> iterator = collection.iterator(); iterator.hasNext() && i < maxLen; i++) {
            if (i > 0)
                builder.append(", ");
            builder.append(iterator.next());
        }
        builder.append("]");
        return builder.toString();
    }

    @Override
    public Single<Boolean> match(final ReactContext ctx, final InOut io) {
        if (null != io.outbound()) {
            return Single.just(false);
        }
        return io.inbound().first().map(fullreq -> this._matcher.match(fullreq.message())).toSingle();
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
            if (this._matcher.match(fullreq.message())) {
                LOG.trace("ResponseWithHeaderonly.react {} matched", fullreq.message());
                return io4response(ctx, io, fullreq);
            } else {
                // not handle this trade
                LOG.trace("ResponseWithHeaderonly.react {} !NOT! matched", fullreq.message());
                return null;
            }
        }).toSingle();
    }

    private InOut io4response(final ReactContext ctx, final InOut orgio, final FullMessage<HttpRequest> orgfullreq) {
        return new InOut() {
            @Override
            public Observable<FullMessage<HttpRequest>> inbound() {
                return orgio.inbound();
            }
            @Override
            public Observable<FullMessage<HttpResponse>> outbound() {
                final HttpResponse response = new DefaultHttpResponse(
                        orgfullreq.message().protocolVersion(), HttpResponseStatus.valueOf(_responseStatus));
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
                if (null!=_extraHeaders) {
                    for (final Map.Entry<String, String> entry : _extraHeaders.entrySet()) {
                        response.headers().set(entry.getKey(), entry.getValue());
                    }
                }
                return Observable.<FullMessage<HttpResponse>>just(new FullMessage<HttpResponse>() {
                    @Override
                    public HttpResponse message() {
                        return response;
                    }
                    @Override
                    public Observable<? extends MessageBody> body() {
                        return Observable.empty();
                    }})
                    .doOnCompleted(() -> {
                        if (_logReact) {
                            LOG.info("RESPOND sendback response directly:\nREQ\n[{}]\nRESP\n[{}]", orgfullreq.message(), response);
                        }
                    }).delaySubscription(orgio.inbound().flatMap(fullmsg -> fullmsg.body()).flatMap(body -> body.content())
                            .compose(StepableUtil.autostep2element2()).doOnNext(bbs -> bbs.dispose()).ignoreElements());
            }};
    }

    @Inject
    MatchRule _matcher;

    @Value("${response.status}")
    int _responseStatus = 200;

    @Inject
    @Named("extraHeaders")
    Map<String, String> _extraHeaders;

    @Value("${response.log}")
    boolean _logReact = true;
}
