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
import org.jocean.xharbor.relay.RelayContext.RelayMemo;
import org.jocean.xharbor.relay.RelayContext.STEP;
import org.jocean.xharbor.relay.RelayContext.RESULT;
import org.jocean.xharbor.spi.Router;
import org.jocean.xharbor.util.BizMemoImpl;
import org.jocean.xharbor.util.TimeInterval10ms_100ms_500ms_1s_5sImpl;
import org.jocean.xharbor.util.TimeIntervalMemo;

/**
 * @author isdom
 */
public class URI2RelayCtxOfRoutingInfo implements Router<URI, RelayContext> {

    public URI2RelayCtxOfRoutingInfo() {
        _mbeanSupport.registerMBean("name=relays", this._level0Memo);
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

    public static interface FunctionMXBean {
        public int getObtainingHttpClient();
        public int getTransferContent();
        public int getRecvResp();
        public int getRelaySuccess();
        public int getRelayFailure();
        public int getSourceCanceled();
        public int getConnectDestinationFailure();
    }
    
    private static class MemoImpl extends BizMemoImpl<MemoImpl, STEP, RESULT> 
        implements RelayMemo, FunctionMXBean {
        
        public MemoImpl() {
            super(STEP.class, RESULT.class);
        }

        public int getObtainingHttpClient() {
            return this.step2Counter(STEP.OBTAINING_HTTPCLIENT).get();
        }
        
        public int getTransferContent() {
            return this.step2Counter(STEP.TRANSFER_CONTENT).get();
        }
        
        public int getRecvResp() {
            return this.step2Counter(STEP.RECV_RESP).get();
        }
        
        public int getRelaySuccess() {
            return this.result2Counter(RESULT.RELAY_SUCCESS).get();
        }
        
        public int getRelayFailure() {
            return this.result2Counter(RESULT.RELAY_FAILURE).get();
        }
        
        public int getSourceCanceled() {
            return this.result2Counter(RESULT.SOURCE_CANCELED).get();
        }
        
        public int getConnectDestinationFailure() {
            return this.result2Counter(RESULT.CONNECTDESTINATION_FAILURE).get();
        }
    }
    
    private final MBeanRegisterSupport _mbeanSupport = 
            new MBeanRegisterSupport("org.jocean:type=router", null);
    
    private <E extends Enum<?>> SimpleCache<Triple<RoutingInfo, URI, E>, TimeIntervalMemo> generateLevel3TTLMemo(final Class<E> cls) {
        return new SimpleCache<Triple<RoutingInfo, URI, E>, TimeIntervalMemo>(
                new Function<Triple<RoutingInfo, URI, E>, TimeIntervalMemo>() {
                    @Override
                    public TimeIntervalMemo apply(final Triple<RoutingInfo, URI, E> input) {
                        return new TimeInterval10ms_100ms_500ms_1s_5sImpl();
                    }}, 
                new Visitor2<Triple<RoutingInfo,URI, E>, TimeIntervalMemo>() {
                    @Override
                    public void visit(final Triple<RoutingInfo,URI, E> triple, final TimeIntervalMemo newMemo)
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
                                newMemo);
                    }});
    }
    
    //  level3
    private final SimpleCache<Triple<RoutingInfo, URI, STEP>, TimeIntervalMemo> _level3StepTTLMemos = 
            generateLevel3TTLMemo(STEP.class);
    
    private final SimpleCache<Triple<RoutingInfo, URI, RESULT>, TimeIntervalMemo> _level3ResultTTLMemos = 
            generateLevel3TTLMemo(RESULT.class);
    
    private final Function<Pair<RoutingInfo, URI>, RelayMemo> _level3MemoMaker = 
            new Function<Pair<RoutingInfo, URI>, RelayMemo>() {
        @Override
        public RelayMemo apply(final Pair<RoutingInfo, URI> input) {
            return new MemoImpl()
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

    private final Visitor2<Pair<RoutingInfo,URI>, RelayMemo> _level3MemoRegister = 
            new Visitor2<Pair<RoutingInfo,URI>, RelayMemo>() {
        @Override
        public void visit(final Pair<RoutingInfo, URI> pair, final RelayMemo newMemo)
                throws Exception {
            final RoutingInfo info = pair.getFirst();
            _mbeanSupport.registerMBean(
                    "path=" + info.getPath() + ",method=" + info.getMethod() 
                    + ",dest=" + pair.getSecond().toString().replaceAll(":", "-"), 
                    newMemo);
        }};

    private final SimpleCache<Pair<RoutingInfo, URI>, RelayMemo> _level3Memos = 
            new SimpleCache<Pair<RoutingInfo, URI>, RelayMemo>(_level3MemoMaker, _level3MemoRegister);
        
    //  level2
    private <E extends Enum<?>> SimpleCache<Pair<RoutingInfo, E>, TimeIntervalMemo> generateLevel2TTLMemo(final Class<E> cls) {
        return new SimpleCache<Pair<RoutingInfo, E>, TimeIntervalMemo>(
                new Function<Pair<RoutingInfo, E>, TimeIntervalMemo>() {
                    @Override
                    public TimeIntervalMemo apply(final Pair<RoutingInfo, E> input) {
                        return new TimeInterval10ms_100ms_500ms_1s_5sImpl();
                    }}, 
                new Visitor2<Pair<RoutingInfo, E>, TimeIntervalMemo>() {
                    @Override
                    public void visit(final Pair<RoutingInfo, E> key, final TimeIntervalMemo newMemo)
                            throws Exception {
                        final RoutingInfo info = key.getFirst();
                        final Enum<?> stepOrResult = key.getSecond();
                        final String category = stepOrResult.getClass().getSimpleName();
                        final String ttl = stepOrResult.name();
                        _mbeanSupport.registerMBean(
                                "path=" + info.getPath() + ",method=" + info.getMethod()
                                + ",category=" + category
                                + ",ttl=" + ttl, 
                                newMemo);
                    }});
    }
    
    private final SimpleCache<Pair<RoutingInfo, STEP>, TimeIntervalMemo> _level2StepTTLMemos = 
            generateLevel2TTLMemo(STEP.class);
    private final SimpleCache<Pair<RoutingInfo, RESULT>, TimeIntervalMemo> _levelResult2TTLMemos = 
            generateLevel2TTLMemo(RESULT.class);
    
    
    private final Function<RoutingInfo, RelayMemo> _level2MemoMaker = 
            new Function<RoutingInfo, RelayMemo>() {
        @Override
        public RelayMemo apply(final RoutingInfo input) {
            return new MemoImpl()
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
        
    private final Visitor2<RoutingInfo, RelayMemo> _level2MemoRegister = 
            new Visitor2<RoutingInfo, RelayMemo>() {
        @Override
        public void visit(final RoutingInfo info, final RelayMemo newMemo)
                throws Exception {
            _mbeanSupport.registerMBean(
                    "path=" + info.getPath() + ",method=" + info.getMethod(),
                    newMemo);
        }};
            
    private final SimpleCache<RoutingInfo, RelayMemo> _level2Memos = 
            new SimpleCache<RoutingInfo, RelayMemo>(_level2MemoMaker, _level2MemoRegister);
    
    //  level1
    private <E extends Enum<?>> SimpleCache<Pair<String, E>, TimeIntervalMemo> generateLevel1TTLMemo(final Class<E> cls) {
        return new SimpleCache<Pair<String, E>, TimeIntervalMemo>(
                new Function<Pair<String, E>, TimeIntervalMemo>() {
                    @Override
                    public TimeIntervalMemo apply(final Pair<String, E> input) {
                        return new TimeInterval10ms_100ms_500ms_1s_5sImpl();
                    }}, 
                new Visitor2<Pair<String, E>, TimeIntervalMemo>() {
                    @Override
                    public void visit(final Pair<String, E> key, final TimeIntervalMemo newMemo)
                            throws Exception {
                        final String path = key.getFirst();
                        final Enum<?> stepOrResult = key.getSecond();
                        final String category = stepOrResult.getClass().getSimpleName();
                        final String ttl = stepOrResult.name();
                        _mbeanSupport.registerMBean(
                                "path=" + path
                                + ",category=" + category
                                + ",ttl=" + ttl, 
                                newMemo);
                    }});
    }
    
    private final SimpleCache<Pair<String, STEP>, TimeIntervalMemo> _level1StepTTLMemos = 
            generateLevel1TTLMemo(STEP.class);
    private final SimpleCache<Pair<String, RESULT>, TimeIntervalMemo> _level1ResultTTLMemos = 
            generateLevel1TTLMemo(RESULT.class);
    
    private final Function<String, RelayMemo> _level1MemoMaker = 
            new Function<String, RelayMemo>() {
        @Override
        public RelayMemo apply(final String path) {
            return new MemoImpl()
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
        
    private final Visitor2<String, RelayMemo> _level1MemoRegister = 
            new Visitor2<String, RelayMemo>() {
        @Override
        public void visit(final String path, final RelayMemo newMemo)
                throws Exception {
            _mbeanSupport.registerMBean("path=" + path, newMemo);
        }};
            
    private final SimpleCache<String, RelayMemo> _level1Memos = 
            new SimpleCache<String, RelayMemo>(_level1MemoMaker, _level1MemoRegister);
    
    //  level0
    private <E extends Enum<?>> SimpleCache<E, TimeIntervalMemo> generateLevel0TTLMemo(final Class<E> cls) {
        return new SimpleCache<E, TimeIntervalMemo>(
                new Function<E, TimeIntervalMemo>() {
                    @Override
                    public TimeIntervalMemo apply(final E input) {
                        return new TimeInterval10ms_100ms_500ms_1s_5sImpl();
                    }}, 
                new Visitor2<E, TimeIntervalMemo>() {
                    @Override
                    public void visit(final E stepOrResult, final TimeIntervalMemo newMemo)
                            throws Exception {
                        final String category = stepOrResult.getClass().getSimpleName();
                        final String ttl = stepOrResult.name();
                        _mbeanSupport.registerMBean(
                                "category=" + category
                                + ",ttl=" + ttl, 
                                newMemo);
                    }});
    }
    
    private final SimpleCache<STEP, TimeIntervalMemo> _level0StepTTLMemos = 
            generateLevel0TTLMemo(STEP.class);
    private final SimpleCache<RESULT, TimeIntervalMemo> _level0ResultTTLMemos = 
            generateLevel0TTLMemo(RESULT.class);

    private RelayMemo _level0Memo = new MemoImpl()
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
