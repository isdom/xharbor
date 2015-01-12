/**
 * 
 */
package org.jocean.xharbor.route;

import java.net.URI;

import org.jocean.idiom.Function;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.SimpleCache;
import org.jocean.idiom.Tuple;
import org.jocean.idiom.Visitor2;
import org.jocean.j2se.MBeanRegisterSupport;
import org.jocean.xharbor.relay.RelayContext;
import org.jocean.xharbor.relay.RelayContext.RESULT;
import org.jocean.xharbor.relay.RelayContext.RelayMemo;
import org.jocean.xharbor.relay.RelayContext.STEP;
import org.jocean.xharbor.spi.Router;
import org.jocean.xharbor.util.BizMemoImpl;
import org.jocean.xharbor.util.Rangeable;
import org.jocean.xharbor.util.TIMemoImpl;
import org.jocean.xharbor.util.TimeIntervalMemo;

import com.google.common.collect.Range;

/**
 * @author isdom
 */
public class URI2RelayCtxOfRoutingInfo implements Router<URI, RelayContext> {

    public URI2RelayCtxOfRoutingInfo() {
        _mbeanSupport.registerMBean("name=relays", this._level0Memo.createMBean());
    }
    
    @Override
    public RelayContext calculateRoute(final URI uri, final Context routectx) {
        if (null != uri) {
            final RoutingInfo info = routectx.getProperty("routingInfo");
            final RelayContext.RelayMemo memo = 
                    InterfaceUtils.combineImpls(RelayContext.RelayMemo.class, 
                    this._bizMemos.get(Tuple.of(info, uri)),
                    this._bizMemos.get(Tuple.of(info)),
                    this._bizMemos.get(Tuple.of(info.getPath())),
                    this._level0Memo
                    );
            
            return new RelayContext() {

                @Override
                public URI relayTo() {
                    return uri;
                }

                @Override
                public RelayMemo memo() {
                    return memo;
                }};
        }
        else {
            return null;
        }
    }

    private enum Range_10ms_100ms_500ms_1s_5s implements Rangeable<Long> {
        range_1_lt10ms(Range.closedOpen(0L, 10L)),
        range_2_lt100ms(Range.closedOpen(10L, 100L)),
        range_3_lt500ms(Range.closedOpen(100L, 500L)),
        range_4_lt1s(Range.closedOpen(500L, 1000L)),
        range_5_lt5s(Range.closedOpen(1000L, 5000L)),
        range_6_mt5s(Range.atLeast(5000L)),
        ;

        Range_10ms_100ms_500ms_1s_5s(final Range<Long> range) {
            this._range = range;
        }
        
        @Override
        public Range<Long> range() {
            return _range;
        }
        
        private final Range<Long> _range;
    }
    
    private static class RelayTIMemoImpl extends TIMemoImpl<Range_10ms_100ms_500ms_1s_5s> {
        
        public RelayTIMemoImpl() {
            super(Range_10ms_100ms_500ms_1s_5s.class);
        }
    }
    
    private static class RelayMemoImpl extends BizMemoImpl<RelayMemoImpl, STEP, RESULT> 
        implements RelayMemo {
        
        public RelayMemoImpl() {
            super(STEP.class, RESULT.class);
        }
    }
    
    private final MBeanRegisterSupport _mbeanSupport = 
            new MBeanRegisterSupport("org.jocean:type=router", null);
    
    private Function<Tuple, RelayTIMemoImpl> _ttlMemoMaker = new Function<Tuple, RelayTIMemoImpl>() {
        @Override
        public RelayTIMemoImpl apply(final Tuple tuple) {
            return new RelayTIMemoImpl();
        }};
        
    private Visitor2<Tuple, RelayTIMemoImpl> _ttlMemoRegister = new Visitor2<Tuple, RelayTIMemoImpl>() {
        @Override
        public void visit(final Tuple tuple, final RelayTIMemoImpl memo) throws Exception {
            switch(tuple.size()) {
            case 3:
                registerLevel3TTLmemo((RoutingInfo)tuple.getAt(0), (URI)tuple.getAt(1), (Enum<?>)tuple.getAt(2), memo);
                break;
            case 2:
                if ( tuple.instanceOf(0, RoutingInfo.class) ) {
                    registerLevel2TTLmemo((RoutingInfo)tuple.getAt(0), (Enum<?>)tuple.getAt(1), memo);
                }
                else if (tuple.instanceOf(0, String.class)) {
                    registerLevel1TTLmemo((String)tuple.getAt(0), (Enum<?>)tuple.getAt(1), memo);
                }
                break;
            case 1:
                registerLevel0TTLmemo((Enum<?>)tuple.getAt(0), memo);
                break;
            }
        }};
            
    private void registerLevel3TTLmemo(
            final RoutingInfo info, 
            final URI uri, 
            final Enum<?> stepOrResult,
            final RelayTIMemoImpl memo) {
        final String category = stepOrResult.getClass().getSimpleName();
        final String ttl = stepOrResult.name();
        _mbeanSupport.registerMBean(
                "path=" + info.getPath() 
                + ",method=" + info.getMethod()
                + ",dest=" + uri.toString().replaceAll(":", "-")
                + ",category=" + category
                + ",ttl=" + ttl, 
                memo.createMBean());
    }

    private void registerLevel2TTLmemo(
            final RoutingInfo info, 
            final Enum<?> stepOrResult,
            final RelayTIMemoImpl memo) {
        final String category = stepOrResult.getClass().getSimpleName();
        final String ttl = stepOrResult.name();
        _mbeanSupport.registerMBean(
                "path=" + info.getPath() 
                + ",method=" + info.getMethod()
                + ",category=" + category
                + ",ttl=" + ttl, 
                memo.createMBean());
    }

    private void registerLevel1TTLmemo(
            final String path, 
            final Enum<?> stepOrResult,
            final RelayTIMemoImpl memo) {
        final String category = stepOrResult.getClass().getSimpleName();
        final String ttl = stepOrResult.name();
        _mbeanSupport.registerMBean(
                "path=" + path
                + ",category=" + category
                + ",ttl=" + ttl, 
                memo.createMBean());
    }

    private void registerLevel0TTLmemo(final Enum<?> stepOrResult, final RelayTIMemoImpl memo) {
        final String category = stepOrResult.getClass().getSimpleName();
        final String ttl = stepOrResult.name();
        _mbeanSupport.registerMBean(
                "category=" + category
                + ",ttl=" + ttl, 
                memo.createMBean());
    }

    private SimpleCache<Tuple, RelayTIMemoImpl> _ttlMemos  = 
            new SimpleCache<Tuple, RelayTIMemoImpl>(this._ttlMemoMaker, this._ttlMemoRegister);
    
    private final Function<Tuple, RelayMemoImpl> _bizMemoMaker = 
            new Function<Tuple, RelayMemoImpl>() {
        @Override
        public RelayMemoImpl apply(final Tuple tuple) {
            return new RelayMemoImpl()
                .fillTimeIntervalMemoWith(new Function<Enum<?>, TimeIntervalMemo>() {
                    @Override
                    public TimeIntervalMemo apply(final Enum<?> e) {
                        return _ttlMemos.get(tuple.append(e));
                    }});
        }};

    private final Visitor2<Tuple, RelayMemoImpl> _bizMemoRegister = 
            new Visitor2<Tuple, RelayMemoImpl>() {
        @Override
        public void visit(final Tuple tuple, final RelayMemoImpl newMemo)
                throws Exception {
            switch(tuple.size()) {
            case 2:
                registerLevel3BizMemo((RoutingInfo)tuple.getAt(0), (URI)tuple.getAt(1), newMemo);
                break;
            case 1:
                if ( tuple.instanceOf(0, RoutingInfo.class) ) {
                    registerLevel2BizMemo((RoutingInfo)tuple.getAt(0), newMemo);
                }
                else if (tuple.instanceOf(0, String.class)) {
                    registerLevel1BizMemo((String)tuple.getAt(0), newMemo);
                }
                break;
            }
        }};
        
    private void registerLevel3BizMemo(
            final RoutingInfo info, 
            final URI dest,
            final RelayMemoImpl newMemo) {
        _mbeanSupport.registerMBean(
                "path=" + info.getPath() + ",method=" + info.getMethod() 
                + ",dest=" + dest.toString().replaceAll(":", "-"), 
                newMemo.createMBean());
    }
    
    private void registerLevel2BizMemo(
            final RoutingInfo info, 
            final RelayMemoImpl newMemo) {
        _mbeanSupport.registerMBean(
                "path=" + info.getPath() + ",method=" + info.getMethod(),
                newMemo.createMBean());
    }
    
    private void registerLevel1BizMemo(
            final String path, 
            final RelayMemoImpl newMemo) {
        _mbeanSupport.registerMBean("path=" + path, newMemo.createMBean());
    }
    
    private final SimpleCache<Tuple, RelayMemoImpl> _bizMemos = 
            new SimpleCache<Tuple, RelayMemoImpl>(this._bizMemoMaker, this._bizMemoRegister);
    
    private RelayMemoImpl _level0Memo = new RelayMemoImpl()
        .fillTimeIntervalMemoWith(new Function<Enum<?>, TimeIntervalMemo>() {
            @Override
            public TimeIntervalMemo apply(final Enum<?> e) {
                return _ttlMemos.get(Tuple.of(e));
            }});
}
