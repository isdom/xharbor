/**
 * 
 */
package org.jocean.xharbor.util;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public abstract class BizMemoImpl<IMPL extends BizMemoImpl<?,E>, E extends Enum<?>> implements BizMemo<E> {
    
    private static final Logger LOG = LoggerFactory
            .getLogger(BizMemoImpl.class);
    
    public BizMemoImpl(final Class<E> clsEnum) {
        this._counters = new AtomicInteger[getValuesOf(clsEnum).length];
        this._memos = new TimeIntervalMemo[this._counters.length];
        for ( int idx = 0; idx < this._counters.length; idx++) {
            this._counters[idx] = new AtomicInteger(0);
            this._memos[idx] = TimeIntervalMemo.NOP;
        }
    }

    /**
     * @param clsEnum
     * @return
     * @throws NoSuchMethodException
     * @throws IllegalAccessException
     * @throws InvocationTargetException
     */
    @SuppressWarnings("unchecked")
    private E[] getValuesOf(final Class<E> clsEnum) {
        try {
            final Method valuesMethod = clsEnum.getDeclaredMethod("values");
            final E[] values = (E[])valuesMethod.invoke(null);
            return values;
        } catch (Exception e) {
            LOG.error("exception when invoke enum({})'s static method values, detail:{}",
                    clsEnum, ExceptionUtils.exception2detail(e));
            return (E[]) Array.newInstance(clsEnum, 0);
        }
    }

    @Override
    public void beginBizStep(final E step) {
        this._counters[step.ordinal()].incrementAndGet();
    }

    @Override
    public void endBizStep(final E step, final long ttl) {
        this._counters[step.ordinal()].decrementAndGet();
        this._memos[step.ordinal()].recordInterval(ttl);
    }

    @Override
    public void incBizResult(final E result, final long ttl) {
        this._counters[result.ordinal()].incrementAndGet();
        this._memos[result.ordinal()].recordInterval(ttl);
    }
    
    protected AtomicInteger enum2Counter(final E e) {
        return this._counters[e.ordinal()];
    }
    
    @SuppressWarnings("unchecked")
    public IMPL setTimeIntervalMemoOfEnum(final E e, final TimeIntervalMemo memo) {
        this._memos[e.ordinal()] = memo;
        return (IMPL)this;
    }
    
    private final AtomicInteger[] _counters;
    private final TimeIntervalMemo[] _memos;
}
