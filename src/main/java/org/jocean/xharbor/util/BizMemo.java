package org.jocean.xharbor.util;


public interface BizMemo<STEP extends Enum<STEP>, RESULT extends Enum<RESULT>> {
    public void beginBizStep(final STEP step);
    public void endBizStep(final STEP step, final long ttl);
    
    public void incBizResult(final RESULT result, final long ttl);
    
    public Object createMBean();
}
