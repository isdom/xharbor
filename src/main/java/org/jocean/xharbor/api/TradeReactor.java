package org.jocean.xharbor.api;

import org.jocean.http.server.HttpServerBuilder.HttpTrade;

import io.netty.handler.codec.http.HttpObject;
import rx.Observable;
import rx.Single;

public interface TradeReactor {
    public interface InOut {
        public Observable<? extends HttpObject> inbound();
        public Observable<? extends HttpObject> outbound();
    }
    
    public Single<? extends InOut> react(final HttpTrade trade, final InOut io);
}
