/**
 * 
 */
package org.jocean.xharbor.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jocean.event.api.AbstractFlow;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.FlowLifecycleListener;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.httpclient.api.Guide;
import org.jocean.httpclient.api.Guide.GuideReactor;
import org.jocean.httpclient.api.HttpClient;
import org.jocean.httpclient.api.HttpClient.HttpReactor;
import org.jocean.idiom.Detachable;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.StopWatch;
import org.jocean.idiom.ValidationId;
import org.jocean.idiom.block.Blob;
import org.jocean.xharbor.relay.RelayContext.RESULT;
import org.jocean.xharbor.relay.RelayContext.STEP;
import org.jocean.xharbor.spi.Router;
import org.jocean.xharbor.spi.Router.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 * 
 */
/*
 * start to obtain httpclient  -->  send request & content --> recv resp & content
 * |<----------ttlObtaining------>|                                                |   --+
 * |                              |<---ttlTransferContent---->|                    |     +---> STEP time cost
 * |                                                          |<----ttlRecvResp--->|   --+
 * +-------------------------------------------------------------------------------+
 * |<-ttlConnectDestinationFailure->                                               |   --+
 * |<---------------ttlSourceCanceled------------------------>                     |     |
 * |<---------------ttlRelayFailure--------------------------------->              |     +---> WHOLE time cost
 * |<------------------------------------ttlRelaySuccess-------------------------->|   --+
 */
class RelayFlow extends AbstractFlow<RelayFlow> {

    private static final Logger LOG = LoggerFactory
            .getLogger(RelayFlow.class);
    
    private static class RouterCtxImpl implements Router.Context {
        private final HashMap<String, Object> _map = new HashMap<String, Object>();
        
        @Override
        public <V> Context setProperty(final String key, final V obj) {
            _map.put(key, obj);
            return this;
        }
        
        @SuppressWarnings("unchecked")
        @Override
        public <V> V getProperty(String key) {
            return (V)_map.get(key);
        }
        
        @Override
        public Map<String, Object> getProperties() {
            return _map;
        }
        
        public void clear() {
            _map.clear();
        }
    }

    public RelayFlow(
            final Router<HttpRequest, RelayContext> router, 
            final Guide guide, 
            final ChannelHandlerContext channelCtx) {
        this._guide = guide;
        this._router = router;
        this._channelCtx = channelCtx;
        
        addFlowLifecycleListener(RELAY_LIFECYCLE_LISTENER);
    }

    private final class ONHTTPLOST {
        public ONHTTPLOST(final Runnable ifHttpLost) {
            this._ifHttpLost = ifHttpLost;
        }
        
        @OnEvent(event = "onHttpClientLost")
        private BizStep onHttpLost(final int guideId)
                throws Exception {
            if (!isValidGuideId(guideId)) {
                return currentEventHandler();
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("http for {} lost.", _relayCtx.relayTo());
            }
            if (null != this._ifHttpLost) {
                try {
                    this._ifHttpLost.run();
                }
                catch(Throwable e) {
                    LOG.warn("exception when invoke {}, detail: {}", 
                            this._ifHttpLost, ExceptionUtils.exception2detail(e));
                }
            }
            try {
                _channelCtx.close();
            }
            catch(Throwable e) {
                LOG.warn("exception when close {}, detail: {}", 
                        _channelCtx, ExceptionUtils.exception2detail(e));
            }
            return null;
        }
        
        private final Runnable _ifHttpLost;
    }
    
    private void safeDetachHttp() {
        if (null != this._guide) {
            try {
                this._guide.detach();
            } catch (Throwable e) {
                LOG.warn(
                        "exception when detach http handle for uri:{}, detail:{}",
                        this._relayCtx.relayTo(), ExceptionUtils.exception2detail(e));
            }
//            this._guide = null;
        }
    }

    private final class ONDETACH {
        public ONDETACH(final Runnable ifDetached) {
            this._ifDetached = ifDetached;
        }
        
        @OnEvent(event = "detach")
        private BizStep onDetach() throws Exception {
            if (LOG.isDebugEnabled()) {
                LOG.debug("relay for uri:{} progress canceled", _relayCtx.relayTo());
            }
            safeDetachHttp();
            if (null != this._ifDetached) {
                try {
                    this._ifDetached.run();
                }
                catch(Throwable e) {
                    LOG.warn("exception when invoke {}, detail: {}", 
                            this._ifDetached, ExceptionUtils.exception2detail(e));
                }
            }
            try {
                _channelCtx.close();
            }
            catch(Throwable e) {
                LOG.warn("exception when close {}, detail: {}", 
                        _channelCtx, ExceptionUtils.exception2detail(e));
            }
            return null;
        }
        
        private final Runnable _ifDetached;
    }

    public final BizStep WAIT = new BizStep("relay.WAIT") {

        @OnEvent(event = "sendHttpRequest")
        private BizStep sendHttpRequest(final HttpRequest httpRequest) {

            _watch4Step.start();
            _watch4Result.start();
            
            final RouterCtxImpl reouterctx = new RouterCtxImpl();
            _relayCtx = _router.calculateRoute(httpRequest, reouterctx);
            reouterctx.clear();
            
            _relayCtx.memo().beginBizStep(STEP.ROUTING);
            _relayCtx.memo().endBizStep(STEP.ROUTING, _watch4Step.stopAndRestart());
            if ( null == _relayCtx.relayTo() ) {
                LOG.warn("can't found matched dest uri for request {}, just close client http connection ({}).", 
                        httpRequest, _channelCtx.channel());
                _channelCtx.close();
                _relayCtx.memo().incBizResult(RESULT.NO_ROUTING, _watch4Result.stopAndRestart());
                return  null;
            }
            
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("dispatch to ({}) for request({})", _relayCtx.relayTo(), httpRequest);
            }
            
            _relayCtx.memo().beginBizStep(STEP.OBTAINING_HTTPCLIENT);
            _httpRequest = ReferenceCountUtil.retain(httpRequest);
            updateTransferHttpRequestState(_httpRequest);
            
            startObtainHttpClient();
            return OBTAINING;
        }
    }
    .handler(handlersOf(new ONDETACH(new Runnable() {
        @Override
        public void run() {
            _relayCtx.memo().incBizResult(RESULT.SOURCE_CANCELED, -1);
        }})))
    .freeze();

    private final BizStep OBTAINING = new BizStep("relay.OBTAINING") {
        @OnEvent(event = "sendHttpContent")
        private BizStep sendHttpContent(final HttpContent httpContent) {

            _contents.add(ReferenceCountUtil.retain(httpContent));
            updateTransferHttpRequestState(httpContent);
            
            return currentEventHandler();
        }
        
        @OnEvent(event = "onHttpClientObtained")
        private BizStep onHttpObtained(
                final int guideId,
                final HttpClient httpclient) {
            if (!isValidGuideId(guideId)) {
                return currentEventHandler();
            }

            _relayCtx.memo().endBizStep(STEP.OBTAINING_HTTPCLIENT, _watch4Step.stopAndRestart());
            _relayCtx.memo().beginBizStep(STEP.TRANSFER_CONTENT);
            
            if (LOG.isDebugEnabled()) {
                LOG.debug("send http request {} & contents size: {}", _httpRequest, _contents.size());
            }
            
            _httpClient = httpclient;
            
            try {
                httpclient.sendHttpRequest(_httpClientId.updateIdAndGet(),
                        _httpRequest, genHttpReactor());
            }
            catch (Exception e) {
                LOG.error(
                        "state({})/{}: exception when sendHttpRequest, detail:{}",
                        currentEventHandler().getName(), currentEvent(),
                        ExceptionUtils.exception2detail(e));
            }
            for ( HttpContent content : _contents) {
                try {
                    httpclient.sendHttpContent(content);
                    ReferenceCountUtil.safeRelease(content);
                }
                catch (Exception e) {
                    LOG.error(
                            "state({})/{}: exception when sendHttpContent, detail:{}",
                            currentEventHandler().getName(), currentEvent(),
                            ExceptionUtils.exception2detail(e));
                }
            }
            _contents.clear();
            
            if ( !_transferHttpRequestComplete ) {
                return TRANSFERCONTENT;
            }
            else {
                _relayCtx.memo().endBizStep(STEP.TRANSFER_CONTENT, _watch4Step.stopAndRestart());
                tryStartForceFinishedTimer();
                _relayCtx.memo().beginBizStep(STEP.RECV_RESP);
                return RECVRESP;
            }
        }
    }
    .handler(handlersOf(new ONHTTPLOST(new Runnable() {
        @Override
        public void run() {
            _relayCtx.memo().endBizStep(STEP.OBTAINING_HTTPCLIENT, -1);
            _relayCtx.memo().incBizResult(RESULT.CONNECTDESTINATION_FAILURE, _watch4Result.stopAndRestart());
        }})))
    .handler(handlersOf(new ONDETACH(new Runnable() {
        @Override
        public void run() {
            _relayCtx.memo().endBizStep(STEP.OBTAINING_HTTPCLIENT, -1);
            _relayCtx.memo().incBizResult(RESULT.SOURCE_CANCELED, _watch4Result.stopAndRestart());
        }})))
    .freeze();

    private final BizStep TRANSFERCONTENT = new BizStep("relay.TRANSFERCONTENT") {
        @OnEvent(event = "sendHttpContent")
        private BizStep sendHttpContent(final HttpContent httpContent) {

            try {
                _httpClient.sendHttpContent(httpContent);
            }
            catch (Exception e) {
                LOG.error(
                        "state({})/{}: exception when sendHttpContent, detail:{}",
                        currentEventHandler().getName(), currentEvent(),
                        ExceptionUtils.exception2detail(e));
            }
            updateTransferHttpRequestState(httpContent);
            
            if ( !_transferHttpRequestComplete ) {
                return currentEventHandler();
            }
            else {
                tryStartForceFinishedTimer();
                _relayCtx.memo().endBizStep(STEP.TRANSFER_CONTENT, _watch4Step.stopAndRestart());
                _relayCtx.memo().beginBizStep(STEP.RECV_RESP);
                return RECVRESP;
            }
        }
    }
    .handler(handlersOf(new ONHTTPLOST(new Runnable() {
        @Override
        public void run() {
            _relayCtx.memo().endBizStep(STEP.TRANSFER_CONTENT, -1);
            _relayCtx.memo().incBizResult(RESULT.RELAY_FAILURE, _watch4Result.stopAndRestart());
        }})))
    .handler(handlersOf(new ONDETACH(new Runnable() {
        @Override
        public void run() {
            _relayCtx.memo().endBizStep(STEP.TRANSFER_CONTENT, -1);
            _relayCtx.memo().incBizResult(RESULT.SOURCE_CANCELED, _watch4Result.stopAndRestart());
        }})))
    .freeze();
        
    private final BizStep RECVRESP = new BizStep("relay.RECVRESP") {
        @OnEvent(event = "onHttpResponseReceived")
        private BizStep responseReceived(final int httpClientId,
                final HttpResponse response) {
            if (!isValidHttpClientId(httpClientId)) {
                return currentEventHandler();
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("channel for {} recv response {}", _relayCtx.relayTo(), response);
            }
            _channelCtx.write(ReferenceCountUtil.retain(response));
            return RECVCONTENT;
        }
    }
    .handler(handlersOf(new ONHTTPLOST(new Runnable() {
        @Override
        public void run() {
            _relayCtx.memo().endBizStep(STEP.RECV_RESP, -1);
            _relayCtx.memo().incBizResult(RESULT.RELAY_FAILURE, _watch4Result.stopAndRestart());
        }})))
    .handler(handlersOf(new ONDETACH(new Runnable() {
        @Override
        public void run() {
            _relayCtx.memo().endBizStep(STEP.RECV_RESP, -1);
            _relayCtx.memo().incBizResult(RESULT.SOURCE_CANCELED, _watch4Result.stopAndRestart());
        }})))
    .freeze();

    private final BizStep RECVCONTENT = new BizStep("relay.RECVCONTENT") {
        @OnEvent(event = "onHttpContentReceived")
        private BizStep contentReceived(final int httpClientId,
                final HttpContent content) throws Exception {
            if (!isValidHttpClientId(httpClientId)) {
                return currentEventHandler();
            }
            
            // content 的内容仅保证在事件 onHttpContentReceived 处理方法中有效
            // 而channelCtx.write完成后，会主动调用 ReferenceCountUtil.release 释放content
            // 因此需要先使用 ReferenceCountUtil.retain 增加一次引用计数
            _channelCtx.write(ReferenceCountUtil.retain(content));
            return RECVCONTENT;
        }

        @OnEvent(event = "onLastHttpContentReceived")
        private BizStep lastContentReceived(final int httpClientId,
                final LastHttpContent content) throws Exception {
            if (!isValidHttpClientId(httpClientId)) {
                return currentEventHandler();
            }

            _relayCtx.memo().endBizStep(STEP.RECV_RESP, _watch4Step.stopAndRestart());
            
            //  release relay's http client
            safeDetachHttp();
            
            // content 的内容仅保证在事件 onLastHttpContentReceived 处理方法中有效
            // 而channelCtx.writeAndFlush完成后，会主动调用 ReferenceCountUtil.release 释放content
            // 因此需要先使用 ReferenceCountUtil.retain 增加一次引用计数
            final ChannelFuture future = _channelCtx.writeAndFlush(ReferenceCountUtil.retain(content));
            if ( !HttpHeaders.isKeepAlive( _httpRequest ) ) {
                future.addListener(ChannelFutureListener.CLOSE);
            }
            _relayCtx.memo().incBizResult(RESULT.RELAY_SUCCESS, _watch4Result.stopAndRestart());
            return null;
        }
    }
    .handler(handlersOf(new ONHTTPLOST(new Runnable() {
        @Override
        public void run() {
            _relayCtx.memo().endBizStep(STEP.RECV_RESP, -1);
            _relayCtx.memo().incBizResult(RESULT.RELAY_FAILURE, _watch4Result.stopAndRestart());
        }})))
    .handler(handlersOf(new ONDETACH(new Runnable() {
        @Override
        public void run() {
            _relayCtx.memo().endBizStep(STEP.RECV_RESP, -1);
            _relayCtx.memo().incBizResult(RESULT.SOURCE_CANCELED, _watch4Result.stopAndRestart());
        }})))
    .freeze();

    @SuppressWarnings("unchecked")
    private HttpReactor<Integer> genHttpReactor() {
        return (HttpReactor<Integer>) this
                .queryInterfaceInstance(HttpReactor.class);
    }

    private void tryStartForceFinishedTimer() {
        if (null == this._forceFinishedTimer && this._timeoutFromActived > 0) {
            this._forceFinishedTimer = this.selfExectionLoop().schedule(
                    new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (LOG.isDebugEnabled()) {
                                    LOG.debug(
                                            "uri:{} force finished timeout, so force detach.",
                                            _relayCtx.relayTo());
                                }
                                _forceFinishedTimer = null;
                                selfEventReceiver().acceptEvent("detach");
                            } catch (Exception e) {
                                LOG.warn(
                                        "exception when acceptEvent detach by force finished for uri:{}, detail:{}",
                                        _relayCtx.relayTo(),
                                        ExceptionUtils.exception2detail(e));
                            }
                        }
                    }, this._timeoutFromActived);
        }
    }

    private void startObtainHttpClient() {
        this._guide.obtainHttpClient(
                this._guideId.updateIdAndGet(),
                genGuideReactor(),
                new Guide.DefaultRequirement()
                        .uri(this._relayCtx.relayTo())
                        .priority(0)
                );
    }

    /**
     * @return
     */
    @SuppressWarnings("unchecked")
    private GuideReactor<Integer> genGuideReactor() {
        return (GuideReactor<Integer>) this
                .queryInterfaceInstance(GuideReactor.class);
    }

    private boolean isValidGuideId(final int guideId) {
        final boolean ret = this._guideId.isValidId(guideId);
        if (!ret) {
            if (LOG.isTraceEnabled()) {
                LOG.trace(
                        "RelayFlow({})/{}/{}: special guide id({}) is !NOT! current guide id ({}), just ignore.",
                        this, currentEventHandler().getName(), currentEvent(),
                        guideId, this._guideId);
            }
        }
        return ret;
    }

    private boolean isValidHttpClientId(final int httpClientId) {
        final boolean ret = this._httpClientId.isValidId(httpClientId);
        if (!ret) {
            if (LOG.isTraceEnabled()) {
                LOG.trace(
                        "RelayFlow({})/{}/{}: special httpclient id({}) is !NOT! current httpclient id ({}), just ignore.",
                        this, currentEventHandler().getName(), currentEvent(),
                        httpClientId, this._httpClientId);
            }
        }
        return ret;
    }

    private void updateTransferHttpRequestState(final HttpRequest httpRequest) {
        if ( httpRequest instanceof FullHttpRequest ) {
            this._transferHttpRequestComplete = true;
        }
    }
    
    private void updateTransferHttpRequestState(final HttpContent content) {
        if ( content instanceof LastHttpContent ) {
            this._transferHttpRequestComplete = true;
        }
    }
    
    private static final FlowLifecycleListener<RelayFlow> RELAY_LIFECYCLE_LISTENER = 
            new FlowLifecycleListener<RelayFlow>() {

        @Override
        public void afterEventReceiverCreated(
                final RelayFlow flow, final EventReceiver receiver)
                throws Exception {
        }

        @Override
        public void afterFlowDestroy(final RelayFlow flow)
                throws Exception {
            if (null != flow._forceFinishedTimer) {
                flow._forceFinishedTimer.detach();
                flow._forceFinishedTimer = null;
            }
            ReferenceCountUtil.safeRelease(flow._httpRequest);
            for (HttpContent content : flow._contents) {
                ReferenceCountUtil.safeRelease(content);
            }
            flow._contents.clear();
        }
    };
    
    private final Guide _guide;
    private final Router<HttpRequest, RelayContext> _router;
    private final ChannelHandlerContext _channelCtx;
    private HttpRequest _httpRequest;
    
    private RelayContext _relayCtx;
    private HttpClient _httpClient;
    private final List<HttpContent> _contents = new ArrayList<HttpContent>();
    private boolean _transferHttpRequestComplete = false;
    
    private final ValidationId _guideId = new ValidationId();
    private final ValidationId _httpClientId = new ValidationId();
    private Detachable _forceFinishedTimer;
    private long _timeoutFromActived = -1;
    private final StopWatch _watch4Step = new StopWatch();
    private final StopWatch _watch4Result = new StopWatch();
}
