package org.jocean.xharbor.spi;

public interface Router<ROUTECTX, RELAYCTX> {
    public RELAYCTX calculateRoute(final ROUTECTX routectx);
}
