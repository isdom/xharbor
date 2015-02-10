/**
 * 
 */
package org.jocean.xharbor.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.jocean.event.api.AbstractFlow;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.FlowLifecycleListener;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.httpclient.api.Guide;
import org.jocean.httpclient.api.Guide.GuideReactor;
import org.jocean.httpclient.api.GuideBuilder;
import org.jocean.httpclient.api.HttpClient;
import org.jocean.httpclient.api.HttpClient.HttpReactor;
import org.jocean.idiom.Detachable;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.ProxyBuilder;
import org.jocean.idiom.Slf4jLoggerSource;
import org.jocean.idiom.StopWatch;
import org.jocean.idiom.ValidationId;
import org.jocean.xharbor.api.Dispatcher;
import org.jocean.xharbor.api.RelayMemo;
import org.jocean.xharbor.api.RelayMemo.RESULT;
import org.jocean.xharbor.api.RelayMemo.STEP;
import org.jocean.xharbor.api.Router;
import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.api.RoutingInfoMemo;
import org.jocean.xharbor.api.ServiceMemo;
import org.jocean.xharbor.api.Target;
import org.jocean.xharbor.spi.HttpRequestTransformer;
import org.jocean.xharbor.spi.HttpRequestTransformer.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.NOPLogger;

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
class RelayFlow extends AbstractFlow<RelayFlow> implements Slf4jLoggerSource {

    private static final String MONITOR_CHECKALIVE = "monitor://checkalive";

    private static final Logger LOG = LoggerFactory
            .getLogger(RelayFlow.class);
    
    @Override
    public String toString() {
        return "RelayFlow [httpRequest=" + _httpRequest + ", guideId="
                + _guideId + ", httpClientId=" + _httpClientId + "]";
    }

    private final ProxyBuilder<Logger> _proxyLogger = new ProxyBuilder<Logger>(Logger.class);
    
    @Override
    public Logger getLogger() {
        return this._proxyLogger.buildProxy();
    }
    
    private static class RouterCtxImpl implements Router.Context {
        private final HashMap<String, Object> _map = new HashMap<String, Object>();
        
        @Override
        public <V> RouterCtxImpl setProperty(final String key, final V obj) {
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
            final Router<HttpRequest, Dispatcher> router, 
            final RelayMemo.Builder memoBuilder,
            final ServiceMemo       serviceMemo, 
            final RoutingInfoMemo   noRoutingMemo,
            final GuideBuilder      guideBuilder,
            final boolean           checkResponseStatus,
            final boolean           showInfoLog,
            final HttpRequestTransformer.Builder transformerBuilder
            ) {
        this._proxyLogger.setImpl(LOG);
        this._checkResponseStatus = checkResponseStatus;
        this._showInfoLog = showInfoLog;
        this._router = router;
        this._memoBuilder = memoBuilder;
        this._serviceMemo = serviceMemo;
        this._noRoutingMemo = noRoutingMemo;
        this._guideBuilder = guideBuilder;
        this._transformerBuilder = transformerBuilder;
        
        addFlowLifecycleListener(RELAY_LIFECYCLE_LISTENER);
    }
    
    RelayFlow attach(
            final ChannelHandlerContext channelCtx,
            final HttpRequest httpRequest) {
        this._httpRequest = ReferenceCountUtil.retain(httpRequest);
        this._channelCtx = channelCtx;
        updateRecvHttpRequestState(this._httpRequest);
        if (null != this._transformerBuilder) {
            this._transformer = this._transformerBuilder.build(httpRequest);
        }
        return this;
    }

    private final class ONHTTPLOST {
        public ONHTTPLOST(final Callable<BizStep> ifHttpLost) {
            this._ifHttpLost = ifHttpLost;
        }
        
        @OnEvent(event = "onHttpClientLost")
        private BizStep onHttpLost(final int guideId)
                throws Exception {
            if (!isValidGuideId(guideId)) {
                return currentEventHandler();
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("{}/{}: http for {} lost.", 
                        currentEventHandler().getName(), currentEvent(), 
                        safeGetServiceUri());
            }
            if (null != this._ifHttpLost) {
                try {
                    return this._ifHttpLost.call();
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
            setEndReason("relay.HTTPLOST");
            return null;
        }
        
        private final Callable<BizStep> _ifHttpLost;
    }
    
    private void safeDetachHttp() {
        if (null != this._guide) {
            try {
                this._guide.detach();
            } catch (Throwable e) {
                LOG.warn(
                        "exception when detach http handle for uri:{}, detail:{}",
                        this.safeGetServiceUri(), ExceptionUtils.exception2detail(e));
            }
            this._guide = null;
        }
    }

    private final class ONDETACH {
        public ONDETACH(final Runnable ifDetached) {
            this._ifDetached = ifDetached;
        }
        
        @OnEvent(event = "detach")
        private BizStep onDetach() throws Exception {
            if (LOG.isDebugEnabled()) {
                LOG.debug("relay for uri:{} progress canceled", safeGetServiceUri());
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

    private final Object CACHE_HTTPCONTENT = new Object() {
        @OnEvent(event = "sendHttpContent")
        private BizStep onSendHttpContent(final HttpContent httpContent) {

            updateRecvHttpRequestState(httpContent);
            _contents.add(ReferenceCountUtil.retain(httpContent));
            
            return currentEventHandler();
        }
    };
    
    public final BizStep WAIT = new BizStep("relay.WAIT") {
        @OnEvent(event = "startRelay")
        private BizStep startRelay() {

            _watch4Step.start();
            _watch4Result.start();
            
            final RouterCtxImpl routectx = 
                    new RouterCtxImpl().setProperty("serviceMemo", _serviceMemo);
            
            final Dispatcher dispatcher = _router.calculateRoute(_httpRequest, routectx);
            final RoutingInfo info = routectx.getProperty("routingInfo");
            routectx.clear();
            
            _target = null != dispatcher ? dispatcher.dispatch() : null;
            
            if ( null == _target ) {
                LOG.warn("can't found matched target service for request:[{}]\njust return 200 OK for client http connection ({}).", 
                        _httpRequest, _channelCtx.channel());
                _noRoutingMemo.incRoutingInfo(info);
                setEndReason("relay.NOROUTING");
                return  waitforRequestFinished();
            }
            if (MONITOR_CHECKALIVE.equalsIgnoreCase(_target.serviceUri().toString())) {
                setEndReason("relay.CHECKALIVE."+_target.serviceUri().toString().replace(':', '-'));
                return  waitforRequestFinished();
            }
            
            _memo = _memoBuilder.build(_target, info);
            
            _memo.beginBizStep(STEP.ROUTING);
            _memo.endBizStep(STEP.ROUTING, _watch4Step.stopAndRestart());
            
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("dispatch to ({}) for request({})", safeGetServiceUri(), _httpRequest);
            }
            
            _memo.beginBizStep(STEP.OBTAINING_HTTPCLIENT);
            
            startObtainHttpClient();
            return OBTAINING;
        }
    }
    .handler(handlersOf(CACHE_HTTPCONTENT))
    .handler(handlersOf(new ONDETACH(new Runnable() {
        @Override
        public void run() {
            _memo.incBizResult(RESULT.SOURCE_CANCELED, -1);
            LOG.warn("SOURCE_CANCELED\ncost:[{}]s\nrequest:[{}]\ndispatch to:[{}]",
                    -1, _httpRequest, safeGetServiceUri());
            setEndReason("relay.SOURCE_CANCELED");
        }})))
    .freeze();

    private BizStep waitforRequestFinished() {
        if (isRecvHttpRequestComplete()) {
            responseDefault200OK();
            return null;
        }
        else {
            return RESP_200OK;
        }
    }

    public final BizStep RESP_200OK = new BizStep("relay.RESP_200OK") {
        @OnEvent(event = "sendHttpContent")
        private BizStep onSendHttpContent(final HttpContent httpContent) {
            updateRecvHttpRequestState(httpContent);
            if (isRecvHttpRequestComplete()) {
                responseDefault200OK();
                return null;
            }
            else {
                return currentEventHandler();
            }
        }
    }
    .handler(handlersOf(new ONDETACH(new Runnable() {
        @Override
        public void run() {
            memoDetachResult();
        }})))
    .freeze();
    
    private final BizStep OBTAINING = new BizStep("relay.OBTAINING") {
        @OnEvent(event = "onHttpClientObtained")
        private BizStep onHttpObtained(
                final int guideId,
                final HttpClient httpclient) {
            if (!isValidGuideId(guideId)) {
                return currentEventHandler();
            }

            _memo.endBizStep(STEP.OBTAINING_HTTPCLIENT, _watch4Step.stopAndRestart());
            _memo.beginBizStep(STEP.TRANSFER_CONTENT);
            
            if (LOG.isDebugEnabled()) {
                LOG.debug("send http request {} & contents size: {}", _httpRequest, _contents.size());
            }
            
            _httpClient = httpclient;
            
            if (needTransformHttpRequest()) {
                if (isRecvHttpRequestComplete() ) {
                    tryTransformAndReplaceHttpRequestOnce();
                }
                else {
                    return TRANSFERCONTENT;
                }
            }
            
            transferHttpRequestAndContents();
            
            if ( !isRecvHttpRequestComplete() ) {
                return TRANSFERCONTENT;
            }
            else {
                _memo.endBizStep(STEP.TRANSFER_CONTENT, _watch4Step.stopAndRestart());
                tryStartForceFinishedTimer();
                _memo.beginBizStep(STEP.RECV_RESP);
                return RECVRESP;
            }
        }
    }
    .handler(handlersOf(CACHE_HTTPCONTENT))
    .handler(handlersOf(new ONHTTPLOST(new Callable<BizStep>() {
        @Override
        public BizStep call() throws Exception {
            _memo.endBizStep(STEP.OBTAINING_HTTPCLIENT, -1);
            return launchRetry(memoHttplostResult(RESULT.CONNECTDESTINATION_FAILURE, true));
        }})))
    .handler(handlersOf(new ONDETACH(new Runnable() {
        @Override
        public void run() {
            _memo.endBizStep(STEP.OBTAINING_HTTPCLIENT, -1);
            memoDetachResult();
        }})))
    .freeze();

    private final BizStep TRANSFERCONTENT = new BizStep("relay.TRANSFERCONTENT") {
        @OnEvent(event = "sendHttpContent")
        private BizStep onSendHttpContent(final HttpContent httpContent) {

            updateRecvHttpRequestState(httpContent);
            _contents.add(ReferenceCountUtil.retain(httpContent));
            if (!needTransformHttpRequest()) {
                transferHttpContent(httpContent);
            }
            
            if ( !isRecvHttpRequestComplete() ) {
                return currentEventHandler();
            }
            else {
                if (needTransformHttpRequest()) {
                    tryTransformAndReplaceHttpRequestOnce();
                    transferHttpRequestAndContents();
                }
                tryStartForceFinishedTimer();
                _memo.endBizStep(STEP.TRANSFER_CONTENT, _watch4Step.stopAndRestart());
                _memo.beginBizStep(STEP.RECV_RESP);
                return RECVRESP;
            }
        }
    }
    .handler(handlersOf(new ONHTTPLOST(new Callable<BizStep>() {
        @Override
        public BizStep call() throws Exception {
            _memo.endBizStep(STEP.TRANSFER_CONTENT, -1);
            return launchRetry(memoHttplostResult(RESULT.RELAY_FAILURE, true));
        }})))
    .handler(handlersOf(new ONDETACH(new Runnable() {
        @Override
        public void run() {
            _memo.endBizStep(STEP.TRANSFER_CONTENT, -1);
            memoDetachResult();
        }})))
    .freeze();
        
    private final BizStep RECVRESP = new BizStep("relay.RECVRESP") {
        @OnEvent(event = "onHttpResponseReceived")
        private BizStep responseReceived(final int httpClientId,
                final HttpResponse response) throws Exception {
            if (!isValidHttpClientId(httpClientId)) {
                return currentEventHandler();
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("channel for {} recv response {}", safeGetServiceUri(), response);
            }
            
            if ( _checkResponseStatus ) {
                if (isHttpClientError(response)) {
                    return retryFor(RESULT.HTTP_CLIENT_ERROR,response);
                }
                
                if (isHttpServerError(response)) {
                    return retryFor(RESULT.HTTP_SERVER_ERROR,response);
                }
            }
            
            //  http response will never be changed, so record it
            _httpResponse = ReferenceCountUtil.retain(response);
            _channelCtx.write(ReferenceCountUtil.retain(_httpResponse));
            
            //  TODO consider 响应为1xx，204，304相应或者head请求，则直接忽视掉消息实体内容。
            //  当满足上述情况时，是否应该直接结束转发流程。
            return buildRecvContentByConnectionEntity();
        }

        private BizStep retryFor(final RESULT result, final HttpResponse response) throws Exception {
            safeDetachHttp();
            _memo.endBizStep(STEP.RECV_RESP, _watch4Step.stopAndRestart());
            final long ttl = _watch4Result.stopAndRestart();
            _memo.incBizResult(result, ttl);
            LOG.warn("{},retry\ncost:[{}]s\nrequest:[{}]\ndispatch to:[{}]\nresponse:[{}]",
                    result.name(), ttl / (float)1000.0, _httpRequest, safeGetServiceUri(), response);
            return launchRetry(ttl);
        }
    }
    .handler(handlersOf(new ONHTTPLOST(new Callable<BizStep>() {
        @Override
        public BizStep call() throws Exception {
            _memo.endBizStep(STEP.RECV_RESP, -1);
            return launchRetry(memoHttplostResult(RESULT.RELAY_FAILURE, true));
        }})))
    .handler(handlersOf(new ONDETACH(new Runnable() {
        @Override
        public void run() {
            _memo.endBizStep(STEP.RECV_RESP, -1);
            memoDetachResult();
        }})))
    .freeze();

    private final BizStep RECVCONTENT_TMPL = new BizStep("relay.RECVCONTENT.template") {
        @OnEvent(event = "onHttpContentReceived")
        private BizStep contentReceived(final int httpClientId,
                final HttpContent content) throws Exception {
            if (!isValidHttpClientId(httpClientId)) {
                return currentEventHandler();
            }
            
            if (LOG.isDebugEnabled()) {
                LOG.debug("channel for {} recv http content {}", safeGetServiceUri(), content);
            }
            
            // content 的内容仅保证在事件 onHttpContentReceived 处理方法中有效
            // 而channelCtx.write完成后，会主动调用 ReferenceCountUtil.release 释放content
            // 因此需要先使用 ReferenceCountUtil.retain 增加一次引用计数
            _channelCtx.write(
                ReferenceCountUtil.retain(content));
            return currentEventHandler();
        }

        @OnEvent(event = "onLastHttpContentReceived")
        private BizStep lastContentReceived(final int httpClientId,
                final LastHttpContent content) throws Exception {
            if (!isValidHttpClientId(httpClientId)) {
                return currentEventHandler();
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("channel for {} recv last http content {}", safeGetServiceUri(), content);
            }
            
            _memo.endBizStep(STEP.RECV_RESP, _watch4Step.stopAndRestart());
            
            //  release relay's http client
            safeDetachHttp();
            
            // content 的内容仅保证在事件 onLastHttpContentReceived 处理方法中有效
            // 而channelCtx.writeAndFlush完成后，会主动调用 ReferenceCountUtil.release 释放content
            // 因此需要先使用 ReferenceCountUtil.retain 增加一次引用计数
            final ChannelFuture future = _channelCtx.writeAndFlush(ReferenceCountUtil.retain(content));
            if ( !HttpHeaders.isKeepAlive( _httpRequest ) ) {
                future.addListener(ChannelFutureListener.CLOSE);
            }
            memoRelaySuccessResult("RELAY_SUCCESS");
            setEndReason("relay.RELAY_SUCCESS");
            return null;
        }
    }
    .handler(handlersOf(new ONDETACH(new Runnable() {
        @Override
        public void run() {
            _memo.endBizStep(STEP.RECV_RESP, -1);
            memoDetachResult();
        }})))
    .freeze();

    private BizStep buildRecvContentByConnectionEntity() {
        //  ref : http://blog.csdn.net/yankai0219/article/details/8269922
        // 1、在http1.1及之后版本。如果是keep alive，则content-length和chunk必然是二选一。
        //   若是非keep alive(Connection: close)，则和http1.0一样, Server侧通过 socket 关闭来表示消息结束
        // 2、在Http 1.0及之前版本中，content-length字段可有可无。Server侧通过 socket 关闭来表示消息结束
        if ( HttpHeaders.isKeepAlive(_httpRequest) ) {
            return RECVCONTENT_TMPL
                    .handler(handlersOf(new ONHTTPLOST(new Callable<BizStep>() {
                        @Override
                        public BizStep call() {
                            try {
                                _channelCtx.close();
                            }
                            catch(Throwable e) {
                                LOG.warn("exception when close {}, detail: {}", 
                                        _channelCtx, ExceptionUtils.exception2detail(e));
                            }
                            _memo.endBizStep(STEP.RECV_RESP, -1);
                            memoHttplostResult(RESULT.RELAY_FAILURE, false);
                            setEndReason("relay.RELAY_FAILURE");
                            return null;
                        }})))
                    .rename("relay.RECVCONTENT.KeepAlive")
                    .freeze();
        }
        else {
            // Connection: close
            return RECVCONTENT_TMPL
                    .handler(handlersOf(new ONHTTPLOST(new Callable<BizStep>() {
                        @Override
                        public BizStep call() {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("channel for {} is Connection: close, so just mark RELAY_SUCCESS and close peer.", 
                                        safeGetServiceUri());
                            }
                            _memo.endBizStep(STEP.RECV_RESP, _watch4Step.stopAndRestart());
                            _channelCtx.flush().close();
                            memoRelaySuccessResult("HTTP10.RELAY_SUCCESS");
                            setEndReason("relay.HTTP10.RELAY_SUCCESS");
                            return null;
                        }})))
                    .rename("relay.RECVCONTENT.Close")
                    .freeze();
        }
    }
    
        
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
                                            safeGetServiceUri());
                                }
                                _forceFinishedTimer = null;
                                selfEventReceiver().acceptEvent("detach");
                            } catch (Exception e) {
                                LOG.warn(
                                        "exception when acceptEvent detach by force finished for uri:{}, detail:{}",
                                        safeGetServiceUri(),
                                        ExceptionUtils.exception2detail(e));
                            }
                        }
                    }, this._timeoutFromActived);
        }
    }

    private void startObtainHttpClient() {
        this._guide = this._guideBuilder.createHttpClientGuide();
        
        this._guide.obtainHttpClient(
                this._guideId.updateIdAndGet(),
                genGuideReactor(),
                new Guide.DefaultRequirement()
                        .uri(this._target.serviceUri())
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
            if (LOG.isDebugEnabled()) {
                LOG.debug(
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
            if (LOG.isDebugEnabled()) {
                LOG.debug(
                        "RelayFlow({})/{}/{}: special httpclient id({}) is !NOT! current httpclient id ({}), just ignore.",
                        this, currentEventHandler().getName(), currentEvent(),
                        httpClientId, this._httpClientId);
            }
        }
        return ret;
    }

    private boolean needTransformHttpRequest() {
        return null!=this._transformer;
    }

    private boolean tryTransformAndReplaceHttpRequestOnce() {
        final FullHttpRequest newRequest = transformToFullHttpRequestOnce();
        if (null!=newRequest) {
            ReferenceCountUtil.release(this._httpRequest);
            this._httpRequest = newRequest;
            releaseAllContents();
            //  add transform request count and record from relay begin 
            //  until transform complete 's time cost 
            this._memo.incBizResult(RESULT.TRANSFORM_REQUEST, 
                    this._watch4Result.pauseAndContinue());
            return true;
        }
        else {
            return false;
        }
    }
    
    private FullHttpRequest transformToFullHttpRequestOnce() {
        if (null!=this._transformer) {
            try {
                if (this._httpRequest instanceof FullHttpRequest) {
                    return this._transformer.transform(
                        this._httpRequest, ((FullHttpRequest)this._httpRequest).content());
                }
                else {
                    final ByteBuf[] bufs = new ByteBuf[this._contents.size()];
                    for (int idx = 0; idx<this._contents.size(); idx++) {
                        bufs[idx] = this._contents.get(idx).content().retain();
                    }
                    final ByteBuf content = Unpooled.wrappedBuffer(bufs);
                    
                    try {
                        return this._transformer.transform(this._httpRequest, content);
                    } finally {
                        content.release();
                    }
                }
            } finally {
                this._transformer = null;
            }
        }
        else {
            return null;
        }
    }

    private void updateRecvHttpRequestState(final HttpObject httpObject) {
        if ( (httpObject instanceof FullHttpRequest) 
            || (httpObject instanceof LastHttpContent)) {
            this._recvHttpRequestComplete = true;
        }
    }
    
    private boolean isRecvHttpRequestComplete() {
        return this._recvHttpRequestComplete;
    }

    /**
     * @param ttl
     * @return
     * @throws Exception
     */
    private BizStep launchRetry(final long ttl) throws Exception {
        _memo.incBizResult(RESULT.RELAY_RETRY, ttl);
        selfEventReceiver().acceptEvent("startRelay");
        return WAIT;
    }

    /**
     * @param response
     * @return
     */
    private static boolean isHttpClientError(final HttpResponse response) {
        return response.getStatus().code() >= 400 
            && response.getStatus().code() < 500;
    }

    /**
     * @param response
     * @return
     */
    private static boolean isHttpServerError(final HttpResponse response) {
        return response.getStatus().code() >= 500 
            && response.getStatus().code() < 600;
    }
    
    /**
     * @param flow
     */
    private void releaseAllContents() {
        for (HttpContent content : this._contents) {
            ReferenceCountUtil.safeRelease(content);
        }
        this._contents.clear();
    }

    /**
     * @param httpclient
     */
    private void transferHttpRequest() {
        this._httpRequest.setUri(
            this._target.rewritePath(this._httpRequest.getUri()));
        try {
            this._httpClient.sendHttpRequest(this._httpClientId.updateIdAndGet(),
                this._httpRequest, genHttpReactor());
        }
        catch (Exception e) {
            LOG.error(
                    "state({})/{}: exception when sendHttpRequest, detail:{}",
                    currentEventHandler().getName(), currentEvent(),
                    ExceptionUtils.exception2detail(e));
        }
    }

    /**
     * @param content
     */
    private void transferHttpContent(final HttpContent content) {
        try {
            this._httpClient.sendHttpContent(content);
        }
        catch (Exception e) {
            LOG.error(
                    "state({})/{}: exception when sendHttpContent, detail:{}",
                    currentEventHandler().getName(), currentEvent(),
                    ExceptionUtils.exception2detail(e));
        }
    }

    /**
     * 
     */
    private void transferHttpRequestAndContents() {
        transferHttpRequest();
        for (HttpContent content : _contents) {
            transferHttpContent(content);
        }
    }

    /**
     * @return
     */
    private String safeGetServiceUri() {
        return null != this._target ? this._target.serviceUri().toString() : "non-uri";
    }

    private void responseDefault200OK() {
        final HttpResponse response = new DefaultFullHttpResponse(
                this._httpRequest.getProtocolVersion(), HttpResponseStatus.OK);
        HttpHeaders.setHeader(response, HttpHeaders.Names.CONTENT_LENGTH, 0);
        final ChannelFuture future = this._channelCtx.writeAndFlush(response);
        if ( !HttpHeaders.isKeepAlive( this._httpRequest ) ) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void memoDetachResult() {
        final long ttl = _watch4Result.stopAndRestart();
        _memo.incBizResult(RESULT.SOURCE_CANCELED, ttl);
        LOG.warn("SOURCE_CANCELED\ncost:[{}]s\nrequest:[{}]\ndispatch to:[{}]",
                ttl / (float)1000.0, _httpRequest, safeGetServiceUri());
        setEndReason("relay.SOURCE_CANCELED");
    }

    private long memoHttplostResult(final RESULT result, final boolean isRetry) {
        final long ttl = _watch4Result.stopAndRestart();
        _memo.incBizResult(result, ttl);
        LOG.warn("{},{}\ncost:[{}]s\nrequest:[{}]\ndispatch to:[{}]",
                result.name(), (isRetry ? "retry" : "not retry"), 
                ttl / (float)1000.0, _httpRequest, safeGetServiceUri());
        return ttl;
    }

    private void memoRelaySuccessResult(final String successName) {
        final long ttl = _watch4Result.stopAndRestart();
        _memo.incBizResult(RESULT.RELAY_SUCCESS, ttl);
        if (this._showInfoLog) {
            LOG.info("{}\ncost:[{}]s\nrequest:[{}]\ndispatch to:[{}]\nresponse:[{}]",
                    successName, ttl / (float)1000.0, _httpRequest, safeGetServiceUri(), _httpResponse);
        }
    }

    private static final FlowLifecycleListener<RelayFlow> RELAY_LIFECYCLE_LISTENER = 
            new FlowLifecycleListener<RelayFlow>() {

        @Override
        public void afterEventReceiverCreated(
                final RelayFlow flow, final EventReceiver receiver)
                throws Exception {
            receiver.acceptEvent("startRelay");
        }

        @Override
        public void afterFlowDestroy(final RelayFlow flow)
                throws Exception {
            if (null != flow._forceFinishedTimer) {
                flow._forceFinishedTimer.detach();
                flow._forceFinishedTimer = null;
            }
            ReferenceCountUtil.safeRelease(flow._httpRequest);
            if (null!=flow._httpResponse) {
                ReferenceCountUtil.safeRelease(flow._httpResponse);
            }
            flow.releaseAllContents();
            //  replace logger to nop logger to disable all log message after flow destroy
            flow._proxyLogger.setImpl(NOPLogger.NOP_LOGGER);
        }
    };
    
    private final boolean _checkResponseStatus;
    private final boolean _showInfoLog;
    private final GuideBuilder _guideBuilder;
    private final RelayMemo.Builder _memoBuilder;
    private final ServiceMemo _serviceMemo; 
    private final RoutingInfoMemo _noRoutingMemo;
    private final Router<HttpRequest, Dispatcher> _router;
    private final Builder _transformerBuilder;
    private HttpRequestTransformer _transformer = null;
    
    private HttpRequest _httpRequest;
    private ChannelHandlerContext _channelCtx;
    private HttpResponse _httpResponse = null;
    
    private Target _target;
    private RelayMemo _memo;
    private Guide _guide;
    private HttpClient _httpClient;
    private final List<HttpContent> _contents = new ArrayList<HttpContent>();
    private boolean _recvHttpRequestComplete = false;
    
    private final ValidationId _guideId = new ValidationId();
    private final ValidationId _httpClientId = new ValidationId();
    private Detachable _forceFinishedTimer;
    private long _timeoutFromActived = -1;
    private final StopWatch _watch4Step = new StopWatch();
    private final StopWatch _watch4Result = new StopWatch();
}
