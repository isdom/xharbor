package org.jocean.xharbor.reactor;

import org.jocean.xharbor.api.TradeReactor;

abstract class SingleReactor implements TradeReactor {

    @Override
    public String[] reactItems() {
        return new String[]{toString()};
    }
}
