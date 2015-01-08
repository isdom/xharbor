package org.jocean.xharbor.util;

public interface BizMemo<STEP extends Enum<?>, RESULT extends Enum<?>> {
    public void beginBizStep(final STEP step);
    public void endBizStep(final STEP step, final long ttl);
    
    public void incBizResult(final RESULT result, final long ttl);
}
