package org.jocean.xharbor.reactor;

import rx.Single;

public class NullReactor extends SingleReactor {

    @Override
    public String toString() {
        return "NullReactor";
    }

    @Override
    public Single<Boolean> match(final ReactContext ctx, final InOut io) {
        return Single.just(false);
    }

    @Override
    public Single<? extends InOut> react(final ReactContext ctx, final InOut io) {
        return Single.just(null);
    }

}
