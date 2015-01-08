/**
 * 
 */
package org.jocean.xharbor.util;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public abstract class BizMemoImpl<IMPL extends BizMemoImpl<?,STEP,RESULT>, 
    STEP extends Enum<?>, RESULT extends Enum<?>> 
    implements BizMemo<STEP,RESULT> {
    
    private static final Logger LOG = LoggerFactory
            .getLogger(BizMemoImpl.class);
    
    public BizMemoImpl(final Class<STEP> clsStep, final Class<RESULT> clsResult) {
        this._stepCounters = new AtomicInteger[getValuesOf(clsStep).length];
        this._stepMemos = new TimeIntervalMemo[this._stepCounters.length];
        this._resultCounters = new AtomicInteger[getValuesOf(clsResult).length];
        this._resultMemos = new TimeIntervalMemo[this._resultCounters.length];
        initCountersAndMemos(this._stepCounters, this._stepMemos);
        initCountersAndMemos(this._resultCounters, this._resultMemos);
    }

    private static void initCountersAndMemos(
            final AtomicInteger[] counters, 
            final TimeIntervalMemo[] memos) {
        for ( int idx = 0; idx < counters.length; idx++) {
            counters[idx] = new AtomicInteger(0);
            memos[idx] = TimeIntervalMemo.NOP;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T[] getValuesOf(final Class<T> cls) {
        try {
            final Method valuesMethod = cls.getDeclaredMethod("values");
            final T[] values = (T[])valuesMethod.invoke(null);
            return values;
        } catch (Exception e) {
            LOG.error("exception when invoke enum({})'s static method values, detail:{}",
                    cls, ExceptionUtils.exception2detail(e));
            return (T[]) Array.newInstance(cls, 0);
        }
    }

    @Override
    public void beginBizStep(final STEP step) {
        this._stepCounters[step.ordinal()].incrementAndGet();
    }

    @Override
    public void endBizStep(final STEP step, final long ttl) {
        this._stepCounters[step.ordinal()].decrementAndGet();
        this._stepMemos[step.ordinal()].recordInterval(ttl);
    }

    @Override
    public void incBizResult(final RESULT result, final long ttl) {
        this._resultCounters[result.ordinal()].incrementAndGet();
        this._resultMemos[result.ordinal()].recordInterval(ttl);
    }
    
    protected AtomicInteger step2Counter(final STEP step) {
        return this._stepCounters[step.ordinal()];
    }
    
    protected AtomicInteger result2Counter(final RESULT result) {
        return this._resultCounters[result.ordinal()];
    }
    
    @SuppressWarnings("unchecked")
    public IMPL setTimeIntervalMemoOfStep(final STEP step, final TimeIntervalMemo memo) {
        this._stepMemos[step.ordinal()] = memo;
        return (IMPL)this;
    }
    
    @SuppressWarnings("unchecked")
    public IMPL setTimeIntervalMemoOfResult(final RESULT result, final TimeIntervalMemo memo) {
        this._resultMemos[result.ordinal()] = memo;
        return (IMPL)this;
    }
    
    private final AtomicInteger[] _stepCounters;
    private final TimeIntervalMemo[] _stepMemos;
    private final AtomicInteger[] _resultCounters;
    private final TimeIntervalMemo[] _resultMemos;
    }
