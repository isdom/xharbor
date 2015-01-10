/**
 * 
 */
package org.jocean.xharbor.util;

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
import org.jocean.idiom.ReflectUtils;
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
        this._steps = ReflectUtils.getValuesOf(clsStep);
        this._stepCounters = new AtomicInteger[this._steps.length];
        this._stepMemos = new TimeIntervalMemo[this._steps.length];
        this._results = ReflectUtils.getValuesOf(clsResult);
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
    
    public DynamicMBean createMBean() {
        return new DynamicMBean() {

            @Override
            public Object getAttribute(final String attribute)
                    throws AttributeNotFoundException, MBeanException,
                    ReflectionException {
                final String splited[] = attribute.split(":");
                final String type = splited[0];
                final String name = splited[1];
                return name2Integer(type, name).get();
            }

            @Override
            public void setAttribute(final Attribute attribute)
                    throws AttributeNotFoundException,
                    InvalidAttributeValueException, MBeanException,
                    ReflectionException {
            }

            @Override
            public AttributeList getAttributes(final String[] attributes) {
                return new AttributeList() {
                    private static final long serialVersionUID = 1L;
                    {
                        for ( String attrname : attributes ) {
                            try {
                                this.add( new Attribute(attrname, getAttribute(attrname)));
                            } catch (Exception e) {
                                LOG.warn("exception when create Attribute({}), detail:{}", 
                                        attrname, ExceptionUtils.exception2detail(e));
                            }
                        }
                    }
                };
            }

            @Override
            public AttributeList setAttributes(final AttributeList attributes) {
                return null;
            }

            @Override
            public Object invoke(final String actionName, final Object[] params,
                    final String[] signature) throws MBeanException,
                    ReflectionException {
                return null;
            }

            @Override
            public MBeanInfo getMBeanInfo() {
                final OpenMBeanAttributeInfoSupport[] attributes = new ArrayList<OpenMBeanAttributeInfoSupport>() {
                    private static final long serialVersionUID = 1L;
                {
                    for ( STEP step : _steps ) {
                        this.add(createCountAttribute(step));
                    }
                    for ( RESULT result : _results ) {
                        this.add(createCountAttribute(result));
                    }
                }
                /**
                 * @param stepOrResult
                 * @return
                 */
                private OpenMBeanAttributeInfoSupport createCountAttribute(
                        Enum<?> stepOrResult) {
                    return new OpenMBeanAttributeInfoSupport(
                        stepOrResult.getClass().getSimpleName() + ":" + stepOrResult.name(), 
                        stepOrResult.getClass().getCanonicalName() + "." +stepOrResult.name(), 
                        SimpleType.INTEGER, true, false, 
                        false);
                }}.toArray(new OpenMBeanAttributeInfoSupport[0]);
             
            //No arg constructor     
                final OpenMBeanConstructorInfoSupport[] constructors = new OpenMBeanConstructorInfoSupport[]{
                    new OpenMBeanConstructorInfoSupport("BizMemo", "Constructs a BizMemo instance.", 
                            new OpenMBeanParameterInfoSupport[0])
                };
             
            //Build the info 
                return new OpenMBeanInfoSupport(BizMemoImpl.class.getName(), 
                            "BizMemo - Open MBean", attributes, constructors, 
                            null, null);
            }};
    }
    
    private AtomicInteger name2Integer(final String type, final String name) {
        final STEP step = enumOf( this._clsStep, type, name);
        return ( null != step ) ? this._stepCounters[step.ordinal()] 
                : this._resultCounters[enumOf(this._clsResult, type, name).ordinal()];
    }
    
    private <E extends Enum<E>> E enumOf(
            final Class<E> cls, 
            final String type,
             final String name ) {
        return cls.getSimpleName().equals(type) ? E.valueOf(cls, name) : null;
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
