/**
 * 
 */
package org.jocean.xharbor.util;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.InvalidAttributeValueException;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.ReflectionException;
import javax.management.openmbean.OpenMBeanAttributeInfoSupport;
import javax.management.openmbean.OpenMBeanConstructorInfoSupport;
import javax.management.openmbean.OpenMBeanInfoSupport;
import javax.management.openmbean.OpenMBeanParameterInfoSupport;
import javax.management.openmbean.SimpleType;

import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public abstract class BizMemoImpl<IMPL extends BizMemoImpl<IMPL,STEP,RESULT>, 
    STEP extends Enum<STEP>, RESULT extends Enum<RESULT>> 
    implements BizMemo<STEP,RESULT> {
    
    private static final Logger LOG = LoggerFactory
            .getLogger(BizMemoImpl.class);
    
    public BizMemoImpl(final Class<STEP> clsStep, final Class<RESULT> clsResult) {
        this._clsStep = clsStep;
        this._clsResult = clsResult;
        this._steps = getValuesOf(clsStep);
        this._stepCounters = new AtomicInteger[this._steps.length];
        this._stepMemos = new TimeIntervalMemo[this._steps.length];
        this._results = getValuesOf(clsResult);
        this._resultCounters = new AtomicInteger[this._results.length];
        this._resultMemos = new TimeIntervalMemo[this._results.length];
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
    
    @Override
    public DynamicMBean createMBean() {
        return new DynamicMBean() {

            @Override
            public Object getAttribute(final String attribute)
                    throws AttributeNotFoundException, MBeanException,
                    ReflectionException {
                return name2Integer(attribute).get();
            }

            @Override
            public void setAttribute(Attribute attribute)
                    throws AttributeNotFoundException,
                    InvalidAttributeValueException, MBeanException,
                    ReflectionException {
            }

            @Override
            public AttributeList getAttributes(String[] attributes) {
                return null;
            }

            @Override
            public AttributeList setAttributes(AttributeList attributes) {
                return null;
            }

            @Override
            public Object invoke(String actionName, Object[] params,
                    String[] signature) throws MBeanException,
                    ReflectionException {
                return null;
            }

            @Override
            public MBeanInfo getMBeanInfo() {
                final OpenMBeanAttributeInfoSupport[] attributes = new ArrayList<OpenMBeanAttributeInfoSupport>() {
                    private static final long serialVersionUID = 1L;
                {
                    for ( STEP step : _steps ) {
                        this.add(new OpenMBeanAttributeInfoSupport(step.name(), 
                            step.name(), SimpleType.INTEGER, true, false, 
                            false));
                    }
                    for ( RESULT result : _results ) {
                        this.add(new OpenMBeanAttributeInfoSupport(result.name(), 
                            result.name(), SimpleType.INTEGER, true, false, 
                            false));
                    }
                }}.toArray(new OpenMBeanAttributeInfoSupport[0]);
             
            //No arg constructor     
                final OpenMBeanConstructorInfoSupport[] constructors = new OpenMBeanConstructorInfoSupport[]{
                    new OpenMBeanConstructorInfoSupport("BizMemo", "Constructs a BizMemo instance.", 
                            new OpenMBeanParameterInfoSupport[0])
                };
             
            //Build the info 
                return new OpenMBeanInfoSupport(BizMemoImpl.class.getName(), 
                            "BizMemo - Open - MBean", attributes, constructors, 
                            null, null);
            }};
    }
    
    private AtomicInteger name2Integer(final String name) {
        final STEP step = enumOf( this._clsStep, name);
        return ( null != step ) ? this._stepCounters[step.ordinal()] 
                : this._resultCounters[enumOf(this._clsResult, name).ordinal()];
    }
    
    private <E extends Enum<E>> E enumOf(
            final Class<E> clsStepOrResult, 
            final String name ) {
        try {
            return E.valueOf(clsStepOrResult, name);
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }
    
    private final Class<STEP> _clsStep;
    private final Class<RESULT> _clsResult;
    private final STEP[] _steps;
    private final RESULT[] _results;
    private final AtomicInteger[] _stepCounters;
    private final TimeIntervalMemo[] _stepMemos;
    private final AtomicInteger[] _resultCounters;
    private final TimeIntervalMemo[] _resultMemos;
}
