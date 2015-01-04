/**
 * 
 */
package org.jocean.xharbor.impl;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.idiom.Function;
import org.jocean.idiom.Pair;
import org.jocean.idiom.SimpleCache;
import org.jocean.idiom.Visitor2;
import org.jocean.j2se.MBeanRegisterSupport;
import org.jocean.xharbor.impl.RelayContext.RelayMemo;

/**
 * @author isdom
 *
 */
public class MemoFactoryImpl implements DispatcherImpl.MemoFactory {

    @Override
    public RelayMemo getRelayMemo(String path, URI relayTo) {
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
        
        private final AtomicInteger _obtainingHttpClient = new AtomicInteger(0);
        private final AtomicInteger _transferContent = new AtomicInteger(0);
        private final AtomicInteger _recvResp = new AtomicInteger(0);
        private final AtomicInteger _relaySuccess = new AtomicInteger(0);
        private final AtomicInteger _relayFailure = new AtomicInteger(0);
        private final AtomicInteger _connectDestinationFailure = new AtomicInteger(0);
        private final AtomicInteger _sourceCanceled = new AtomicInteger(0);
    }
    
    private final Function<Pair<String, URI>, RelayMemo> _memoMaker = 
            new Function<Pair<String, URI>, RelayMemo>() {
        @Override
        public RelayMemo apply(final Pair<String, URI> input) {
            return new MemoImpl();
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
            new MBeanRegisterSupport("org.jocean:type=gateway,attr=route", null);
}
