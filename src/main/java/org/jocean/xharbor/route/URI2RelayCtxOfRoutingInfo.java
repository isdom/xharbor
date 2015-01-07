/**
 * 
 */
package org.jocean.xharbor.route;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.idiom.Function;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.Pair;
import org.jocean.idiom.SimpleCache;
import org.jocean.idiom.Triple;
import org.jocean.idiom.Visitor2;
import org.jocean.j2se.MBeanRegisterSupport;
import org.jocean.xharbor.relay.RelayContext;
import org.jocean.xharbor.relay.TimeInterval10ms_100ms_500ms_1s_5sImpl;
import org.jocean.xharbor.relay.TimeIntervalMemo;
import org.jocean.xharbor.relay.RelayContext.RelayMemo;
import org.jocean.xharbor.spi.Router;

/**
 * @author isdom
 * TODO
 * 考虑 HTTP 请求的方法区分: GET/POST/PUT ...... 
 * 实现 Composite RelayMemo，包含 细粒度(path,relayTo) 以及 其上两级的 RelayMemo，分别为 全局 RelayMemo 以及 path 相关的 RelayMemo
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
    
    private static class MemoImpl implements RelayMemo, FunctionMXBean {
        public MemoImpl(final TimeIntervalMemo[] ttlMemos) {
            this._ttlMemos = ttlMemos;
        }
        
        public int getObtainingHttpClient() {
            return this._obtainingHttpClient.get();
        }
        
        public int getTransferContent() {
            return this._transferContent.get();
        }
        
        public int getRecvResp() {
            return this._recvResp.get();
        }
        
        public int getRelaySuccess() {
            return this._relaySuccess.get();
        }
        
        public int getRelayFailure() {
            return this._relayFailure.get();
        }
        
        public int getSourceCanceled() {
            return this._sourceCanceled.get();
        }
        
        public int getConnectDestinationFailure() {
            return this._connectDestinationFailure.get();
        }
        
        public void incObtainingHttpClient() {
            this._obtainingHttpClient.incrementAndGet();
        }
        
        @Override
        public void decObtainingHttpClient() {
            this._obtainingHttpClient.decrementAndGet();
        }

        @Override
        public void incTransferContent() {
            this._transferContent.incrementAndGet();
        }

        @Override
        public void decTransferContent() {
            this._transferContent.decrementAndGet();
        }

        @Override
        public void incRecvResp() {
            this._recvResp.incrementAndGet();
        }

        @Override
        public void decRecvResp() {
            this._recvResp.decrementAndGet();
        }

        @Override
        public void incRelaySuccess() {
            this._relaySuccess.incrementAndGet();
        }

        @Override
        public void incSourceCanceled() {
            this._sourceCanceled.incrementAndGet();
        }

        @Override
        public void incConnectDestinationFailure() {
            this._connectDestinationFailure.incrementAndGet();
        }

        @Override
        public void incRelayFailure() {
            this._relayFailure.incrementAndGet();
        }
        
        @Override
        public void ttl4ObtainingHttpClient(final long ttl) {
            this._ttlMemos[0].recordInterval(ttl);
        }

        @Override
        public void ttl4TransferContent(final long ttl) {
            this._ttlMemos[1].recordInterval(ttl);
        }

        @Override
        public void ttl4RecvResp(final long ttl) {
            this._ttlMemos[2].recordInterval(ttl);
        }

        @Override
        public void ttl4RelaySuccess(final long ttl) {
            this._ttlMemos[3].recordInterval(ttl);
        }

        @Override
        public void ttl4SourceCanceled(final long ttl) {
            this._ttlMemos[4].recordInterval(ttl);
        }

        @Override
        public void ttl4ConnectDestinationFailure(final long ttl) {
            this._ttlMemos[5].recordInterval(ttl);
        }

        @Override
        public void ttl4RelayFailure(final long ttl) {
            this._ttlMemos[6].recordInterval(ttl);
        }
        
        private final AtomicInteger _obtainingHttpClient = new AtomicInteger(0);
        private final AtomicInteger _transferContent = new AtomicInteger(0);
        private final AtomicInteger _recvResp = new AtomicInteger(0);
        private final AtomicInteger _relaySuccess = new AtomicInteger(0);
        private final AtomicInteger _relayFailure = new AtomicInteger(0);
        private final AtomicInteger _connectDestinationFailure = new AtomicInteger(0);
        private final AtomicInteger _sourceCanceled = new AtomicInteger(0);
        private final TimeIntervalMemo[] _ttlMemos;
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
            return new MemoImpl(new TimeIntervalMemo[]{
                    _level3TTLMemos.get(Triple.of(input.getFirst(), input.getSecond(), Pair.of("step","obtainingHttpClient"))),
                    _level3TTLMemos.get(Triple.of(input.getFirst(), input.getSecond(), Pair.of("step","transferContent"))),
                    _level3TTLMemos.get(Triple.of(input.getFirst(), input.getSecond(), Pair.of("step","recvResp"))),
                    _level3TTLMemos.get(Triple.of(input.getFirst(), input.getSecond(), Pair.of("whole","relaySuccess"))),
                    _level3TTLMemos.get(Triple.of(input.getFirst(), input.getSecond(), Pair.of("whole","sourceCanceled"))),
                    _level3TTLMemos.get(Triple.of(input.getFirst(), input.getSecond(), Pair.of("whole","connectDestinationFailure"))),
                    _level3TTLMemos.get(Triple.of(input.getFirst(), input.getSecond(), Pair.of("whole","relayFailure")))
                    });
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
            return new MemoImpl(new TimeIntervalMemo[]{
                    _level2TTLMemos.get(Pair.of(input, Pair.of("step","obtainingHttpClient"))),
                    _level2TTLMemos.get(Pair.of(input, Pair.of("step","transferContent"))),
                    _level2TTLMemos.get(Pair.of(input, Pair.of("step","recvResp"))),
                    _level2TTLMemos.get(Pair.of(input, Pair.of("whole","relaySuccess"))),
                    _level2TTLMemos.get(Pair.of(input, Pair.of("whole","sourceCanceled"))),
                    _level2TTLMemos.get(Pair.of(input, Pair.of("whole","connectDestinationFailure"))),
                    _level2TTLMemos.get(Pair.of(input, Pair.of("whole","relayFailure")))
                    });
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
            return new MemoImpl(new TimeIntervalMemo[]{
                    _level1TTLMemos.get(Pair.of(path, Pair.of("step","obtainingHttpClient"))),
                    _level1TTLMemos.get(Pair.of(path, Pair.of("step","transferContent"))),
                    _level1TTLMemos.get(Pair.of(path, Pair.of("step","recvResp"))),
                    _level1TTLMemos.get(Pair.of(path, Pair.of("whole","relaySuccess"))),
                    _level1TTLMemos.get(Pair.of(path, Pair.of("whole","sourceCanceled"))),
                    _level1TTLMemos.get(Pair.of(path, Pair.of("whole","connectDestinationFailure"))),
                    _level1TTLMemos.get(Pair.of(path, Pair.of("whole","relayFailure")))
                    });
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

    private RelayMemo _level0Memo = new MemoImpl(new TimeIntervalMemo[]{
                _level0TTLMemos.get(Pair.of("step","obtainingHttpClient")),
                _level0TTLMemos.get(Pair.of("step","transferContent")),
                _level0TTLMemos.get(Pair.of("step","recvResp")),
                _level0TTLMemos.get(Pair.of("whole","relaySuccess")),
                _level0TTLMemos.get(Pair.of("whole","sourceCanceled")),
                _level0TTLMemos.get(Pair.of("whole","connectDestinationFailure")),
                _level0TTLMemos.get(Pair.of("whole","relayFailure"))
                });
}
