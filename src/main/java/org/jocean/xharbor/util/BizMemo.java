package org.jocean.xharbor.util;

public interface BizMemo<E extends Enum<?>> {
    public void beginBizStep(final E step);
    public void endBizStep(final E step, final long ttl);
    
    public void incBizResult(final E result, final long ttl);
}
