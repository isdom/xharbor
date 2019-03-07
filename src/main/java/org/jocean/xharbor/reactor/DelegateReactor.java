package org.jocean.xharbor.reactor;

import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;

import org.jocean.idiom.BeanFinder;
import org.jocean.idiom.Ordered;
import org.jocean.xharbor.api.TradeReactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import rx.Single;

public class DelegateReactor implements TradeReactor, Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(DelegateReactor.class);

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("DelegateReactor [ordinal=").append(_ordinal).append(", name=").append(_delegateName)
                .append(", delegate=").append(null != _delegateReactor.get() ? _delegateReactor.get() : "(none)" )
                .append("]");
        return builder.toString();
    }

    @Override
    public String[] reactItems() {
        final TradeReactor delegate = _delegateReactor.get();
        if (null != delegate) {
            final String[] subItems = delegate.reactItems();
            final String[] newItems = new String[subItems.length + 1];
            newItems[0] = "DelegateReactor: ordinal=" + _ordinal + ", name=" + _delegateName;
            System.arraycopy(subItems, 0, newItems, 1, subItems.length);
            return newItems;
        } else {
            return new String[]{toString()};
        }
    }

    @Override
    public Single<Boolean> match(final ReactContext ctx, final InOut io) {
        final TradeReactor delegated = _delegateReactor.get();
        if (null != delegated) {
            return delegated.match(ctx, io);
        } else {
            return findAndSetDelegate().flatMap(reactor -> reactor.match(ctx, io));
        }
    }

    @Override
    public Single<? extends InOut> react(final ReactContext ctx, final InOut io) {
        final TradeReactor delegated = _delegateReactor.get();
        if (null != delegated) {
            return delegated.react(ctx, io);
        } else {
            return findAndSetDelegate().flatMap(reactor -> reactor.react(ctx, io));
        }
    }

    private Single<TradeReactor> findAndSetDelegate() {
        return this._finder.find(this._delegateName, TradeReactor.class).map(reactor -> {
            if (this._delegateReactor.compareAndSet(null, reactor)) {
                LOG.info("found delegate reactor {} with name: {}", reactor, this._delegateName);
                return reactor;
            } else {
                LOG.info("using delegated reactor {} with name: {}", reactor, this._delegateName);
                return this._delegateReactor.get();
            }
        }).toSingle().onErrorReturn(e -> NullReactor.INSTANCE);
    }

    @Override
    public int ordinal() {
        return this._ordinal;
    }

    @Inject
    BeanFinder _finder;

    @Value("${delegate.name}")
    String _delegateName;

    final private AtomicReference<TradeReactor> _delegateReactor = new AtomicReference<>(null);;

    @Value("${priority}")
    int _ordinal = 0;
}
