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
    
    //  level3
    private final SimpleCache<Triple<RoutingInfo, URI, Pair<String,String>>, TimeIntervalMemo> _level3TTLMemos = 
            new SimpleCache<Triple<RoutingInfo, URI, Pair<String,String>>, TimeIntervalMemo>(
                new Function<Triple<RoutingInfo, URI, Pair<String,String>>, TimeIntervalMemo>() {
                    @Override
                    public TimeIntervalMemo apply(final Triple<RoutingInfo, URI, Pair<String,String>> input) {
                        return new TimeInterval10ms_100ms_500ms_1s_5sImpl();
                    }}, 
                new Visitor2<Triple<RoutingInfo,URI, Pair<String,String>>, TimeIntervalMemo>() {
                    @Override
                    public void visit(final Triple<RoutingInfo,URI, Pair<String,String>> triple, final TimeIntervalMemo newMemo)
                            throws Exception {
                        final RoutingInfo info = triple.getFirst();
                        final String category = triple.getThird().getFirst();
                        final String ttl = triple.getThird().getSecond();
                        _mbeanSupport.registerMBean(
                                "path=" + info.getPath() + ",method=" + info.getMethod()
                                + ",dest=" + triple.getSecond().toString().replaceAll(":", "-")
                                + ",category=" + category
                                + ",ttl=" + ttl, 
                                newMemo);
                    }});
    
    private final Function<Pair<RoutingInfo, URI>, RelayMemo> _level3MemoMaker = 
            new Function<Pair<RoutingInfo, URI>, RelayMemo>() {
        @Override
        public RelayMemo apply(final Pair<RoutingInfo, URI> input) {
            return new MemoImpl()
                .setTimeIntervalMemoOfStep(STEP.OBTAINING_HTTPCLIENT,
                    _level3TTLMemos.get(Triple.of(input.getFirst(), input.getSecond(), Pair.of("step","obtainingHttpClient"))))
                .setTimeIntervalMemoOfStep(STEP.TRANSFER_CONTENT,
                    _level3TTLMemos.get(Triple.of(input.getFirst(), input.getSecond(), Pair.of("step","transferContent"))))
                .setTimeIntervalMemoOfStep(STEP.RECV_RESP,
                    _level3TTLMemos.get(Triple.of(input.getFirst(), input.getSecond(), Pair.of("step","recvResp"))))
                .setTimeIntervalMemoOfResult(RESULT.RELAY_SUCCESS,
                    _level3TTLMemos.get(Triple.of(input.getFirst(), input.getSecond(), Pair.of("whole","relaySuccess"))))
                .setTimeIntervalMemoOfResult(RESULT.SOURCE_CANCELED,
                    _level3TTLMemos.get(Triple.of(input.getFirst(), input.getSecond(), Pair.of("whole","sourceCanceled"))))
                .setTimeIntervalMemoOfResult(RESULT.CONNECTDESTINATION_FAILURE,
                    _level3TTLMemos.get(Triple.of(input.getFirst(), input.getSecond(), Pair.of("whole","connectDestinationFailure"))))
                .setTimeIntervalMemoOfResult(RESULT.RELAY_FAILURE,
                    _level3TTLMemos.get(Triple.of(input.getFirst(), input.getSecond(), Pair.of("whole","relayFailure"))));
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
    private final SimpleCache<Pair<RoutingInfo, Pair<String,String>>, TimeIntervalMemo> _level2TTLMemos = 
            new SimpleCache<Pair<RoutingInfo, Pair<String,String>>, TimeIntervalMemo>(
                new Function<Pair<RoutingInfo, Pair<String,String>>, TimeIntervalMemo>() {
                    @Override
                    public TimeIntervalMemo apply(final Pair<RoutingInfo, Pair<String,String>> input) {
                        return new TimeInterval10ms_100ms_500ms_1s_5sImpl();
                    }}, 
                new Visitor2<Pair<RoutingInfo, Pair<String,String>>, TimeIntervalMemo>() {
                    @Override
                    public void visit(final Pair<RoutingInfo, Pair<String,String>> key, final TimeIntervalMemo newMemo)
                            throws Exception {
                        final RoutingInfo info = key.getFirst();
                        final String category = key.getSecond().getFirst();
                        final String ttl = key.getSecond().getSecond();
                        _mbeanSupport.registerMBean(
                                "path=" + info.getPath() + ",method=" + info.getMethod()
                                + ",category=" + category
                                + ",ttl=" + ttl, 
                                newMemo);
                    }});
    
    private final Function<RoutingInfo, RelayMemo> _level2MemoMaker = 
            new Function<RoutingInfo, RelayMemo>() {
        @Override
        public RelayMemo apply(final RoutingInfo input) {
            return new MemoImpl()
                .setTimeIntervalMemoOfStep(STEP.OBTAINING_HTTPCLIENT,
                    _level2TTLMemos.get(Pair.of(input, Pair.of("step","obtainingHttpClient"))))
                .setTimeIntervalMemoOfStep(STEP.TRANSFER_CONTENT,
                    _level2TTLMemos.get(Pair.of(input, Pair.of("step","transferContent"))))
                .setTimeIntervalMemoOfStep(STEP.RECV_RESP,
                    _level2TTLMemos.get(Pair.of(input, Pair.of("step","recvResp"))))
                .setTimeIntervalMemoOfResult(RESULT.RELAY_SUCCESS,
                    _level2TTLMemos.get(Pair.of(input, Pair.of("whole","relaySuccess"))))
                .setTimeIntervalMemoOfResult(RESULT.SOURCE_CANCELED,
                    _level2TTLMemos.get(Pair.of(input, Pair.of("whole","sourceCanceled"))))
                .setTimeIntervalMemoOfResult(RESULT.CONNECTDESTINATION_FAILURE,
                    _level2TTLMemos.get(Pair.of(input, Pair.of("whole","connectDestinationFailure"))))
                .setTimeIntervalMemoOfResult(RESULT.RELAY_FAILURE,
                    _level2TTLMemos.get(Pair.of(input, Pair.of("whole","relayFailure"))));
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
    private final SimpleCache<Pair<String, Pair<String,String>>, TimeIntervalMemo> _level1TTLMemos = 
            new SimpleCache<Pair<String, Pair<String,String>>, TimeIntervalMemo>(
                new Function<Pair<String, Pair<String,String>>, TimeIntervalMemo>() {
                    @Override
                    public TimeIntervalMemo apply(final Pair<String, Pair<String,String>> input) {
                        return new TimeInterval10ms_100ms_500ms_1s_5sImpl();
                    }}, 
                new Visitor2<Pair<String, Pair<String,String>>, TimeIntervalMemo>() {
                    @Override
                    public void visit(final Pair<String, Pair<String,String>> key, final TimeIntervalMemo newMemo)
                            throws Exception {
                        final String path = key.getFirst();
                        final String category = key.getSecond().getFirst();
                        final String ttl = key.getSecond().getSecond();
                        _mbeanSupport.registerMBean(
                                "path=" + path
                                + ",category=" + category
                                + ",ttl=" + ttl, 
                                newMemo);
                    }});
    
    private final Function<String, RelayMemo> _level1MemoMaker = 
            new Function<String, RelayMemo>() {
        @Override
        public RelayMemo apply(final String path) {
            return new MemoImpl()
            .setTimeIntervalMemoOfStep(STEP.OBTAINING_HTTPCLIENT,
                    _level1TTLMemos.get(Pair.of(path, Pair.of("step","obtainingHttpClient"))))
            .setTimeIntervalMemoOfStep(STEP.TRANSFER_CONTENT,
                    _level1TTLMemos.get(Pair.of(path, Pair.of("step","transferContent"))))
            .setTimeIntervalMemoOfStep(STEP.RECV_RESP,
                    _level1TTLMemos.get(Pair.of(path, Pair.of("step","recvResp"))))
            .setTimeIntervalMemoOfResult(RESULT.RELAY_SUCCESS,
                    _level1TTLMemos.get(Pair.of(path, Pair.of("whole","relaySuccess"))))
            .setTimeIntervalMemoOfResult(RESULT.SOURCE_CANCELED,
                    _level1TTLMemos.get(Pair.of(path, Pair.of("whole","sourceCanceled"))))
            .setTimeIntervalMemoOfResult(RESULT.CONNECTDESTINATION_FAILURE,
                    _level1TTLMemos.get(Pair.of(path, Pair.of("whole","connectDestinationFailure"))))
            .setTimeIntervalMemoOfResult(RESULT.RELAY_FAILURE,
                    _level1TTLMemos.get(Pair.of(path, Pair.of("whole","relayFailure"))));
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
    private final SimpleCache<Pair<String,String>, TimeIntervalMemo> _level0TTLMemos = 
            new SimpleCache<Pair<String,String>, TimeIntervalMemo>(
                new Function<Pair<String,String>, TimeIntervalMemo>() {
                    @Override
                    public TimeIntervalMemo apply(final Pair<String,String> input) {
                        return new TimeInterval10ms_100ms_500ms_1s_5sImpl();
                    }}, 
                new Visitor2<Pair<String,String>, TimeIntervalMemo>() {
                    @Override
                    public void visit(final Pair<String,String> key, final TimeIntervalMemo newMemo)
                            throws Exception {
                        final String category = key.getFirst();
                        final String ttl = key.getSecond();
                        _mbeanSupport.registerMBean(
                                "category=" + category
                                + ",ttl=" + ttl, 
                                newMemo);
                    }});

    private RelayMemo _level0Memo = new MemoImpl()
            .setTimeIntervalMemoOfStep(STEP.OBTAINING_HTTPCLIENT,
                    _level0TTLMemos.get(Pair.of("step","obtainingHttpClient")))
            .setTimeIntervalMemoOfStep(STEP.TRANSFER_CONTENT,
                    _level0TTLMemos.get(Pair.of("step","transferContent")))
            .setTimeIntervalMemoOfStep(STEP.RECV_RESP,
                    _level0TTLMemos.get(Pair.of("step","recvResp")))
            .setTimeIntervalMemoOfResult(RESULT.RELAY_SUCCESS,
                    _level0TTLMemos.get(Pair.of("whole","relaySuccess")))
            .setTimeIntervalMemoOfResult(RESULT.SOURCE_CANCELED,
                    _level0TTLMemos.get(Pair.of("whole","sourceCanceled")))
            .setTimeIntervalMemoOfResult(RESULT.CONNECTDESTINATION_FAILURE,
                    _level0TTLMemos.get(Pair.of("whole","connectDestinationFailure")))
            .setTimeIntervalMemoOfResult(RESULT.RELAY_FAILURE,
                    _level0TTLMemos.get(Pair.of("whole","relayFailure")));
}
