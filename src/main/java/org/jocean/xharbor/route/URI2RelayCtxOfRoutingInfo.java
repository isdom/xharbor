/**
 * 
 */
package org.jocean.xharbor.route;

import java.net.URI;

import org.jocean.idiom.Function;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.Pair;
import org.jocean.idiom.SimpleCache;
import org.jocean.idiom.Triple;
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
                    this._level3Memos.get(Pair.of(info, uri)),
                    this._level2Memos.get(info),
                    this._level1Memos.get(info.getPath()),
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
    
    private <E extends Enum<?>> SimpleCache<Triple<RoutingInfo, URI, E>, RelayTIMemoImpl> generateLevel3TTLMemo(final Class<E> cls) {
        return new SimpleCache<Triple<RoutingInfo, URI, E>, RelayTIMemoImpl>(
                new Function<Triple<RoutingInfo, URI, E>, RelayTIMemoImpl>() {
                    @Override
                    public RelayTIMemoImpl apply(final Triple<RoutingInfo, URI, E> input) {
                        return new RelayTIMemoImpl();
                    }}, 
                new Visitor2<Triple<RoutingInfo,URI, E>, RelayTIMemoImpl>() {
                    @Override
                    public void visit(final Triple<RoutingInfo,URI, E> triple, final RelayTIMemoImpl newMemo)
                            throws Exception {
                        final RoutingInfo info = triple.getFirst();
                        final Enum<?> stepOrResult = triple.getThird();
                        final String category = stepOrResult.getClass().getSimpleName();
                        final String ttl = stepOrResult.name();
                        _mbeanSupport.registerMBean(
                                "path=" + info.getPath() + ",method=" + info.getMethod()
                                + ",dest=" + triple.getSecond().toString().replaceAll(":", "-")
                                + ",category=" + category
                                + ",ttl=" + ttl, 
                                newMemo.createMBean());
                    }});
    }
    
    //  level3
    private final SimpleCache<Triple<RoutingInfo, URI, STEP>, RelayTIMemoImpl> _level3StepTTLMemos = 
            generateLevel3TTLMemo(STEP.class);
    
    private final SimpleCache<Triple<RoutingInfo, URI, RESULT>, RelayTIMemoImpl> _level3ResultTTLMemos = 
            generateLevel3TTLMemo(RESULT.class);
    
    private final Function<Pair<RoutingInfo, URI>, RelayMemoImpl> _level3MemoMaker = 
            new Function<Pair<RoutingInfo, URI>, RelayMemoImpl>() {
        @Override
        public RelayMemoImpl apply(final Pair<RoutingInfo, URI> input) {
            return new RelayMemoImpl()
                .setTimeIntervalMemoOfStep(STEP.OBTAINING_HTTPCLIENT,
                    _level3StepTTLMemos.get(Triple.of(input.getFirst(), input.getSecond(), STEP.OBTAINING_HTTPCLIENT)))
                .setTimeIntervalMemoOfStep(STEP.TRANSFER_CONTENT,
                    _level3StepTTLMemos.get(Triple.of(input.getFirst(), input.getSecond(), STEP.TRANSFER_CONTENT)))
                .setTimeIntervalMemoOfStep(STEP.RECV_RESP,
                    _level3StepTTLMemos.get(Triple.of(input.getFirst(), input.getSecond(), STEP.RECV_RESP)))
                .setTimeIntervalMemoOfResult(RESULT.RELAY_SUCCESS,
                    _level3ResultTTLMemos.get(Triple.of(input.getFirst(), input.getSecond(), RESULT.RELAY_SUCCESS)))
                .setTimeIntervalMemoOfResult(RESULT.SOURCE_CANCELED,
                    _level3ResultTTLMemos.get(Triple.of(input.getFirst(), input.getSecond(), RESULT.SOURCE_CANCELED)))
                .setTimeIntervalMemoOfResult(RESULT.CONNECTDESTINATION_FAILURE,
                    _level3ResultTTLMemos.get(Triple.of(input.getFirst(), input.getSecond(), RESULT.CONNECTDESTINATION_FAILURE)))
                .setTimeIntervalMemoOfResult(RESULT.RELAY_FAILURE,
                    _level3ResultTTLMemos.get(Triple.of(input.getFirst(), input.getSecond(), RESULT.RELAY_FAILURE)));
        }};

    private final Visitor2<Pair<RoutingInfo,URI>, RelayMemoImpl> _level3MemoRegister = 
            new Visitor2<Pair<RoutingInfo,URI>, RelayMemoImpl>() {
        @Override
        public void visit(final Pair<RoutingInfo, URI> pair, final RelayMemoImpl newMemo)
                throws Exception {
            final RoutingInfo info = pair.getFirst();
            _mbeanSupport.registerMBean(
                    "path=" + info.getPath() + ",method=" + info.getMethod() 
                    + ",dest=" + pair.getSecond().toString().replaceAll(":", "-"), 
                    newMemo.createMBean());
        }};

    private final SimpleCache<Pair<RoutingInfo, URI>, RelayMemoImpl> _level3Memos = 
            new SimpleCache<Pair<RoutingInfo, URI>, RelayMemoImpl>(_level3MemoMaker, _level3MemoRegister);
        
    //  level2
    private <E extends Enum<?>> SimpleCache<Pair<RoutingInfo, E>, RelayTIMemoImpl> generateLevel2TTLMemo(final Class<E> cls) {
        return new SimpleCache<Pair<RoutingInfo, E>, RelayTIMemoImpl>(
                new Function<Pair<RoutingInfo, E>, RelayTIMemoImpl>() {
                    @Override
                    public RelayTIMemoImpl apply(final Pair<RoutingInfo, E> input) {
                        return new RelayTIMemoImpl();
                    }}, 
                new Visitor2<Pair<RoutingInfo, E>, RelayTIMemoImpl>() {
                    @Override
                    public void visit(final Pair<RoutingInfo, E> key, final RelayTIMemoImpl newMemo)
                            throws Exception {
                        final RoutingInfo info = key.getFirst();
                        final Enum<?> stepOrResult = key.getSecond();
                        final String category = stepOrResult.getClass().getSimpleName();
                        final String ttl = stepOrResult.name();
                        _mbeanSupport.registerMBean(
                                "path=" + info.getPath() + ",method=" + info.getMethod()
                                + ",category=" + category
                                + ",ttl=" + ttl, 
                                newMemo.createMBean());
                    }});
    }
    
    private final SimpleCache<Pair<RoutingInfo, STEP>, RelayTIMemoImpl> _level2StepTTLMemos = 
            generateLevel2TTLMemo(STEP.class);
    private final SimpleCache<Pair<RoutingInfo, RESULT>, RelayTIMemoImpl> _levelResult2TTLMemos = 
            generateLevel2TTLMemo(RESULT.class);
    
    
    private final Function<RoutingInfo, RelayMemoImpl> _level2MemoMaker = 
            new Function<RoutingInfo, RelayMemoImpl>() {
        @Override
        public RelayMemoImpl apply(final RoutingInfo input) {
            return new RelayMemoImpl()
                .setTimeIntervalMemoOfStep(STEP.OBTAINING_HTTPCLIENT,
                    _level2StepTTLMemos.get(Pair.of(input, STEP.OBTAINING_HTTPCLIENT)))
                .setTimeIntervalMemoOfStep(STEP.TRANSFER_CONTENT,
                    _level2StepTTLMemos.get(Pair.of(input, STEP.TRANSFER_CONTENT)))
                .setTimeIntervalMemoOfStep(STEP.RECV_RESP,
                    _level2StepTTLMemos.get(Pair.of(input, STEP.RECV_RESP)))
                .setTimeIntervalMemoOfResult(RESULT.RELAY_SUCCESS,
                    _levelResult2TTLMemos.get(Pair.of(input, RESULT.RELAY_SUCCESS)))
                .setTimeIntervalMemoOfResult(RESULT.SOURCE_CANCELED,
                    _levelResult2TTLMemos.get(Pair.of(input, RESULT.SOURCE_CANCELED)))
                .setTimeIntervalMemoOfResult(RESULT.CONNECTDESTINATION_FAILURE,
                    _levelResult2TTLMemos.get(Pair.of(input, RESULT.CONNECTDESTINATION_FAILURE)))
                .setTimeIntervalMemoOfResult(RESULT.RELAY_FAILURE,
                    _levelResult2TTLMemos.get(Pair.of(input, RESULT.RELAY_FAILURE)));
        }};
        
    private final Visitor2<RoutingInfo, RelayMemoImpl> _level2MemoRegister = 
            new Visitor2<RoutingInfo, RelayMemoImpl>() {
        @Override
        public void visit(final RoutingInfo info, final RelayMemoImpl newMemo)
                throws Exception {
            _mbeanSupport.registerMBean(
                    "path=" + info.getPath() + ",method=" + info.getMethod(),
                    newMemo.createMBean());
        }};
            
    private final SimpleCache<RoutingInfo, RelayMemoImpl> _level2Memos = 
            new SimpleCache<RoutingInfo, RelayMemoImpl>(_level2MemoMaker, _level2MemoRegister);
    
    //  level1
    private <E extends Enum<?>> SimpleCache<Pair<String, E>, RelayTIMemoImpl> generateLevel1TTLMemo(final Class<E> cls) {
        return new SimpleCache<Pair<String, E>, RelayTIMemoImpl>(
                new Function<Pair<String, E>, RelayTIMemoImpl>() {
                    @Override
                    public RelayTIMemoImpl apply(final Pair<String, E> input) {
                        return new RelayTIMemoImpl();
                    }}, 
                new Visitor2<Pair<String, E>, RelayTIMemoImpl>() {
                    @Override
                    public void visit(final Pair<String, E> key, final RelayTIMemoImpl newMemo)
                            throws Exception {
                        final String path = key.getFirst();
                        final Enum<?> stepOrResult = key.getSecond();
                        final String category = stepOrResult.getClass().getSimpleName();
                        final String ttl = stepOrResult.name();
                        _mbeanSupport.registerMBean(
                                "path=" + path
                                + ",category=" + category
                                + ",ttl=" + ttl, 
                                newMemo.createMBean());
                    }});
    }
    
    private final SimpleCache<Pair<String, STEP>, RelayTIMemoImpl> _level1StepTTLMemos = 
            generateLevel1TTLMemo(STEP.class);
    private final SimpleCache<Pair<String, RESULT>, RelayTIMemoImpl> _level1ResultTTLMemos = 
            generateLevel1TTLMemo(RESULT.class);
    
    private final Function<String, RelayMemoImpl> _level1MemoMaker = 
            new Function<String, RelayMemoImpl>() {
        @Override
        public RelayMemoImpl apply(final String path) {
            return new RelayMemoImpl()
            .setTimeIntervalMemoOfStep(STEP.OBTAINING_HTTPCLIENT,
                    _level1StepTTLMemos.get(Pair.of(path, STEP.OBTAINING_HTTPCLIENT)))
            .setTimeIntervalMemoOfStep(STEP.TRANSFER_CONTENT,
                    _level1StepTTLMemos.get(Pair.of(path, STEP.TRANSFER_CONTENT)))
            .setTimeIntervalMemoOfStep(STEP.RECV_RESP,
                    _level1StepTTLMemos.get(Pair.of(path, STEP.RECV_RESP)))
            .setTimeIntervalMemoOfResult(RESULT.RELAY_SUCCESS,
                    _level1ResultTTLMemos.get(Pair.of(path, RESULT.RELAY_SUCCESS)))
            .setTimeIntervalMemoOfResult(RESULT.SOURCE_CANCELED,
                    _level1ResultTTLMemos.get(Pair.of(path, RESULT.SOURCE_CANCELED)))
            .setTimeIntervalMemoOfResult(RESULT.CONNECTDESTINATION_FAILURE,
                    _level1ResultTTLMemos.get(Pair.of(path, RESULT.CONNECTDESTINATION_FAILURE)))
            .setTimeIntervalMemoOfResult(RESULT.RELAY_FAILURE,
                    _level1ResultTTLMemos.get(Pair.of(path, RESULT.RELAY_FAILURE)));
        }};
        
    private final Visitor2<String, RelayMemoImpl> _level1MemoRegister = 
            new Visitor2<String, RelayMemoImpl>() {
        @Override
        public void visit(final String path, final RelayMemoImpl newMemo)
                throws Exception {
            _mbeanSupport.registerMBean("path=" + path, newMemo.createMBean());
        }};
            
    private final SimpleCache<String, RelayMemoImpl> _level1Memos = 
            new SimpleCache<String, RelayMemoImpl>(_level1MemoMaker, _level1MemoRegister);
    
    //  level0
    private <E extends Enum<?>> SimpleCache<E, RelayTIMemoImpl> generateLevel0TTLMemo(final Class<E> cls) {
        return new SimpleCache<E, RelayTIMemoImpl>(
                new Function<E, RelayTIMemoImpl>() {
                    @Override
                    public RelayTIMemoImpl apply(final E input) {
                        return new RelayTIMemoImpl();
                    }}, 
                new Visitor2<E, RelayTIMemoImpl>() {
                    @Override
                    public void visit(final E stepOrResult, final RelayTIMemoImpl newMemo)
                            throws Exception {
                        final String category = stepOrResult.getClass().getSimpleName();
                        final String ttl = stepOrResult.name();
                        _mbeanSupport.registerMBean(
                                "category=" + category
                                + ",ttl=" + ttl, 
                                newMemo.createMBean());
                    }});
    }
    
    private final SimpleCache<STEP, RelayTIMemoImpl> _level0StepTTLMemos = 
            generateLevel0TTLMemo(STEP.class);
    private final SimpleCache<RESULT, RelayTIMemoImpl> _level0ResultTTLMemos = 
            generateLevel0TTLMemo(RESULT.class);

    private RelayMemoImpl _level0Memo = new RelayMemoImpl()
            .setTimeIntervalMemoOfStep(STEP.OBTAINING_HTTPCLIENT,
                    _level0StepTTLMemos.get(STEP.OBTAINING_HTTPCLIENT))
            .setTimeIntervalMemoOfStep(STEP.TRANSFER_CONTENT,
                    _level0StepTTLMemos.get(STEP.TRANSFER_CONTENT))
            .setTimeIntervalMemoOfStep(STEP.RECV_RESP,
                    _level0StepTTLMemos.get(STEP.RECV_RESP))
            .setTimeIntervalMemoOfResult(RESULT.RELAY_SUCCESS,
                    _level0ResultTTLMemos.get(RESULT.RELAY_SUCCESS))
            .setTimeIntervalMemoOfResult(RESULT.SOURCE_CANCELED,
                    _level0ResultTTLMemos.get(RESULT.SOURCE_CANCELED))
            .setTimeIntervalMemoOfResult(RESULT.CONNECTDESTINATION_FAILURE,
                    _level0ResultTTLMemos.get(RESULT.CONNECTDESTINATION_FAILURE))
            .setTimeIntervalMemoOfResult(RESULT.RELAY_FAILURE,
                    _level0ResultTTLMemos.get(RESULT.RELAY_FAILURE));
}
