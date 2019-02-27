package org.jocean.xharbor.reactor;

import org.jocean.xharbor.api.TradeReactor;

import rx.Single;

public class NullReactor implements TradeReactor {

    @Override
    public Single<Boolean> match(final ReactContext ctx, final InOut io) {
        return Single.just(false);
    }

    @Override
    public Single<? extends InOut> react(final ReactContext ctx, final InOut io) {
        return Single.just(null);
    }

}
