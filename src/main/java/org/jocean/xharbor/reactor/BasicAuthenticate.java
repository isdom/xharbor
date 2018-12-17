package org.jocean.xharbor.reactor;

import org.jocean.http.FullMessage;
import org.jocean.http.MessageBody;
import org.jocean.idiom.Pair;
import org.jocean.idiom.StepableUtil;
import org.jocean.xharbor.api.TradeReactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.io.BaseEncoding;

import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import rx.Observable;
import rx.Single;

public class BasicAuthenticate implements TradeReactor {
    private static final Logger LOG = LoggerFactory.getLogger(BasicAuthenticate.class);

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("BasicAuthenticate [matcher=").append(_matcher).append("]");
        return builder.toString();
    }

    public BasicAuthenticate(
            final MatchRule matcher,
            final String user,
            final String password,
            final String strWWWAuthenticate) {
        this._matcher = matcher;
        this._user = user;
        this._password = password;
        this._strWWWAuthenticate = strWWWAuthenticate;
    }

    @Override
    public Single<? extends InOut> react(final ReactContext ctx, final InOut io) {
        LOG.trace("try {} for trade {}", this, ctx.trade());
        if (null != io.outbound()) {
            return Single.<InOut>just(null);
        }
        return io.inbound().first().map(fullreq -> {
            if (_matcher.match(fullreq.message())) {
                if (isAuthorizeSuccess(fullreq.message(), _user, _password)) {
                    return null;
                } else {
                    // response 401 Unauthorized
                    return io4Unauthorized(ctx, io, fullreq);
                }
            } else {
                // not handle this trade
                return null;
            }
        }).toSingle();
    }

    private InOut io4Unauthorized(final ReactContext ctx, final InOut orgio, final FullMessage<HttpRequest> orgfullreq) {
        return new InOut() {
            @Override
            public Observable<FullMessage<HttpRequest>> inbound() {
                return orgio.inbound();
            }

            @Override
            public Observable<FullMessage<HttpResponse>> outbound() {
                return Observable.<FullMessage<HttpResponse>>just(new FullMessage<HttpResponse>() {
                    @Override
                    public HttpResponse message() {
                        final HttpResponse response = new DefaultHttpResponse(
                                orgfullreq.message().protocolVersion(), HttpResponseStatus.UNAUTHORIZED);
                        response.headers().set(HttpHeaderNames.WWW_AUTHENTICATE,  "Basic realm=\"" + _strWWWAuthenticate + "\"");
                        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);

                        return response;
                    }
                    @Override
                    public Observable<? extends MessageBody> body() {
                        return Observable.empty();
                    }}).delaySubscription(orgio.inbound().flatMap(fullmsg -> fullmsg.body()).flatMap(body -> body.content())
                        .compose(StepableUtil.autostep2element2()).doOnNext(bbs -> bbs.dispose()).ignoreElements());
            }
        };
    }

    private boolean isAuthorizeSuccess(
            final HttpRequest httpRequest,
            final String authUser,
            final String authPassword) {
        final String authorization = httpRequest.headers().get(HttpHeaderNames.AUTHORIZATION);
        if ( null != authorization) {
            final String userAndPassBase64Encoded = extractBasicAuthData(authorization);
            if ( null != userAndPassBase64Encoded ) {
                final Pair<String, String> userAndPass = getUserAndPassForBasicAuth(userAndPassBase64Encoded);
                if (null != userAndPass) {
                    final boolean ret = (userAndPass.getFirst().equals(authUser)
                            && userAndPass.getSecond().equals(authPassword));
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("httpRequest [{}] basic authorization {}, input:{}/{}, setted auth:{}/{}",
                                httpRequest, (ret ? "success" : "failure"),
                                userAndPass.getFirst(), userAndPass.getSecond(),
                                authUser, authPassword);
                    }
                    return ret;
                }
            }
        }
        return false;
    }

    private static String extractBasicAuthData(final String authorization) {
        if (authorization.startsWith("Basic")) {
            final String[] authFields = authorization.split(" ");
            if (authFields.length>=2) {
                return authFields[1];
            }
        }
        return null;
    }

    private static Pair<String, String> getUserAndPassForBasicAuth(
            final String userAndPassBase64Encoded) {
        final String userAndPass = new String(BaseEncoding.base64().decode(userAndPassBase64Encoded),
                Charsets.UTF_8);
        final String[] fields = userAndPass.split(":");
        return fields.length == 2 ? Pair.of(fields[0], fields[1]) : null;
    }

    private final MatchRule _matcher;
    private final String _user;
    private final String _password;
    private final String _strWWWAuthenticate;
}
