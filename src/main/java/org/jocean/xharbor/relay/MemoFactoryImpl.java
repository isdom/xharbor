/**
 * 
 */
package org.jocean.xharbor.relay;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.idiom.Function;
import org.jocean.idiom.Pair;
import org.jocean.idiom.SimpleCache;
import org.jocean.idiom.Triple;
import org.jocean.idiom.Visitor2;
import org.jocean.j2se.MBeanRegisterSupport;
import org.jocean.xharbor.relay.RelayContext.RelayMemo;
import org.jocean.xharbor.route.URIs2RelayCtxRouter;

/**
 * @author isdom
 * TODO
 * 考虑 HTTP 请求的方法区分: GET/POST/PUT ...... 
 * 实现 Composite RelayMemo，包含 细粒度(path,relayTo) 以及 其上两级的 RelayMemo，分别为 全局 RelayMemo 以及 path 相关的 RelayMemo
 */
public class MemoFactoryImpl implements URIs2RelayCtxRouter.MemoFactory {

    @Override
    public RelayMemo getRelayMemo(final String path, final URI relayTo) {
        return this._memos.get(Pair.of(path, relayTo));
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
    
    private final SimpleCache<Triple<String, URI, String>, TimeIntervalMemo> _ttlmemos = 
            new SimpleCache<Triple<String, URI, String>, TimeIntervalMemo>(
                new Function<Triple<String, URI, String>, TimeIntervalMemo>() {
                    @Override
                    public TimeIntervalMemo apply(final Triple<String, URI, String> input) {
                        return new TimeInterval10ms_100ms_500ms_1s_5sImpl();
                    }}, 
                new Visitor2<Triple<String,URI, String>, TimeIntervalMemo>() {
                    @Override
                    public void visit(final Triple<String,URI, String> triple, final TimeIntervalMemo newMemo)
                            throws Exception {
                        _mbeanSupport.registerMBean("path=" + triple.getFirst() 
                                + ",dest=" + triple.getSecond().toString().replaceAll(":", "-")
                                + ",ttl=" + triple.getThird(), 
                                newMemo);
                    }});
    
    private final Function<Pair<String, URI>, RelayMemo> _memoMaker = 
            new Function<Pair<String, URI>, RelayMemo>() {
        @Override
        public RelayMemo apply(final Pair<String, URI> input) {
            return new MemoImpl(new TimeIntervalMemo[]{
                    _ttlmemos.get(Triple.of(input.getFirst(), input.getSecond(), "obtainingHttpClient")),
                    _ttlmemos.get(Triple.of(input.getFirst(), input.getSecond(), "transferContent")),
                    _ttlmemos.get(Triple.of(input.getFirst(), input.getSecond(), "recvResp")),
                    _ttlmemos.get(Triple.of(input.getFirst(), input.getSecond(), "relaySuccess")),
                    _ttlmemos.get(Triple.of(input.getFirst(), input.getSecond(), "sourceCanceled")),
                    _ttlmemos.get(Triple.of(input.getFirst(), input.getSecond(), "connectDestinationFailure")),
                    _ttlmemos.get(Triple.of(input.getFirst(), input.getSecond(), "relayFailure"))
                    });
        }};

    private final Visitor2<Pair<String,URI>, RelayMemo> _memoRegister = 
            new Visitor2<Pair<String,URI>, RelayMemo>() {
        @Override
        public void visit(final Pair<String, URI> pair, final RelayMemo newMemo)
                throws Exception {
            _mbeanSupport.registerMBean("path=" + pair.getFirst() + ",dest=" + pair.getSecond().toString().replaceAll(":", "-"), 
                    newMemo);
        }};

    private final SimpleCache<Pair<String, URI>, RelayMemo> _memos = 
            new SimpleCache<Pair<String, URI>, RelayMemo>(this._memoMaker, this._memoRegister);
        
    private final MBeanRegisterSupport _mbeanSupport = 
            new MBeanRegisterSupport("org.jocean:type=router", null);
}
