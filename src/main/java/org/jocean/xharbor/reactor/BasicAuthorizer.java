package org.jocean.xharbor.reactor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.Ordered;
import org.jocean.idiom.Pair;
import org.jocean.idiom.Regexs;
import org.jocean.xharbor.api.TradeReactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.io.BaseEncoding;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import rx.Observable;
import rx.Single;
import rx.functions.Func1;

public class BasicAuthorizer implements TradeReactor, Ordered {
    private static final Logger LOG = LoggerFactory
            .getLogger(BasicAuthorizer.class);

    @Override
    public int ordinal() {
        return 0;
    }
    
    public BasicAuthorizer(
            final String pathPattern, 
            final String user, 
            final String password,
            final String strWWWAuthenticate) {
        this._pathPattern = Regexs.safeCompilePattern(pathPattern);
        this._user = user;
        this._password = password;
        this._strWWWAuthenticate = strWWWAuthenticate;
    }

    @Override
    public Single<? extends InOut> react(final HttpTrade trade, final InOut io) {
        if (null != io.outbound()) {
            return Single.<InOut>just(null);
        }
        return io.inbound().compose(RxNettys.asHttpRequest())
                .map(new Func1<HttpRequest, InOut>() {
                    @Override
                    public InOut call(final HttpRequest req) {
                        if (null == req) {
                            return null;
                        } else {
                            final Matcher matcher = _pathPattern.matcher(req.uri());
                            if ( matcher.find() ) {
                                if (isAuthorizeSuccess(req, _user, _password)) {
                                    return null;
                                } else {
                                    // response 401 Unauthorized
                                    return io4Unauthorized(io, req);
                                }
                            } else {
                                //  not handle this trade
                                return null;
                            }
                        }
                    }})
                .toSingle();
    }

    private InOut io4Unauthorized(final InOut originalio, 
            final HttpRequest originalreq) {
        return new InOut() {
            @Override
            public Observable<? extends HttpObject> inbound() {
                return originalio.inbound();
            }
            @Override
            public Observable<? extends HttpObject> outbound() {
                return RxNettys.response401Unauthorized(
                        originalreq.protocolVersion(), 
                        _strWWWAuthenticate)
                    .delaySubscription(originalio.inbound().ignoreElements());
            }};
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

    private final Pattern _pathPattern;
    private final String _user;
    private final String _password;
    private final String _strWWWAuthenticate;
}
