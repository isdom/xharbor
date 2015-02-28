/**
 * 
 */
package org.jocean.xharbor.relay;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import org.jocean.event.api.AbstractFlow;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.FlowLifecycleListener;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.httpclient.api.Guide;
import org.jocean.httpclient.api.Guide.GuideReactor;
import org.jocean.httpclient.api.HttpClient;
import org.jocean.httpclient.api.HttpClient.HttpReactor;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.ProxyBuilder;
import org.jocean.idiom.Slf4jLoggerSource;
import org.jocean.idiom.StopWatch;
import org.jocean.idiom.Visitor;
import org.jocean.xharbor.api.Dispatcher;
import org.jocean.xharbor.api.RelayMemo;
import org.jocean.xharbor.api.RelayMemo.RESULT;
import org.jocean.xharbor.api.RelayMemo.STEP;
import org.jocean.xharbor.api.Router;
import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.api.RoutingInfoMemo;
import org.jocean.xharbor.api.Target;
import org.jocean.xharbor.spi.HttpRequestTransformer;
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
public class RelayFlow extends AbstractFlow<RelayFlow> implements Slf4jLoggerSource {

    private static final String MONITOR_CHECKALIVE = "monitor://checkalive";

    private static final Logger LOG = LoggerFactory
            .getLogger(RelayFlow.class);
    
    
    private final FlowLifecycleListener<RelayFlow> memoUnauthorizedAction = new FlowLifecycleListener<RelayFlow>() {
        @Override
        public void afterEventReceiverCreated(RelayFlow flow,
                EventReceiver receiver) throws Exception {
        }
        @Override
        public void afterFlowDestroy(RelayFlow flow)
                throws Exception {
            _memo.incBizResult(RESULT.HTTP_UNAUTHORIZED, _watch4Result.stopAndRestart());
        }};
        
    @Override
    public String toString() {
        return "RelayFlow [httpRequest=" + _requestData + ", httpClient="
                + _httpClientWrapper + "]";
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
            final RoutingInfoMemo   noRoutingMemo
            ) {
        this._proxyLogger.setImpl(LOG);
        this._router = router;
        this._memoBuilder = memoBuilder;
        this._noRoutingMemo = noRoutingMemo;
        
        addFlowLifecycleListener(LIFECYCLE_LISTENER);
    }
    
    RelayFlow attach(
            final ChannelHandlerContext channelCtx,
            final HttpRequest httpRequest) {
        this._requestData.setHttpRequest(httpRequest);
        this._channelCtx = channelCtx;
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
                return BizStep.CURRENT_BIZSTEP;
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("{}/{}: http for {} lost.", 
                        currentEventHandler().getName(), currentEvent(), 
                        serviceUri());
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

    private final class ONDETACH {
        public ONDETACH(final Runnable ifDetached) {
            this._ifDetached = ifDetached;
        }
        
        @OnEvent(event = "detach")
        private BizStep onDetach() throws Exception {
            if (LOG.isDebugEnabled()) {
                LOG.debug("relay for uri:{} progress canceled", serviceUri());
            }
            _httpClientWrapper.detachHttpClient();
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
        @OnEvent(event = "onHttpContent")
        private BizStep cacheHttpContent(final HttpContent httpContent) {
            _requestData.addContent(httpContent);
            return BizStep.CURRENT_BIZSTEP;
        }
    };
    
    public final BizStep WAIT = new BizStep("relay.WAIT") {
        @OnEvent(event = "startRelay")
        private BizStep startRelay() {

            _watch4Step.start();
            _watch4Result.start();
            
            final RouterCtxImpl routectx = new RouterCtxImpl();
            
            final Dispatcher dispatcher = _router.calculateRoute(_requestData.request(), routectx);
            final RoutingInfo info = routectx.getProperty("routingInfo");
            routectx.clear();
            
            _target = null != dispatcher ? dispatcher.dispatch() : null;
            
            if ( null == _target ) {
                LOG.warn("can't found matched target service for request:[{}]\njust return 200 OK for client http connection ({}).", 
                        _requestData, _channelCtx.channel());
                _noRoutingMemo.incRoutingInfo(info);
                setEndReason("relay.NOROUTING");
                return  recvFullRequestAndResponse200OK();
            }
            
            if (MONITOR_CHECKALIVE.equalsIgnoreCase(_target.serviceUri().toString())) {
                setEndReason("relay.CHECKALIVE."+_target.serviceUri().toString().replace(':', '-'));
                return  recvFullRequestAndResponse200OK();
            }
            
            _memo = _memoBuilder.build(_target, info);
            
            if (_target.isNeedAuthorization(_requestData.request())) {
                setEndReason("relay.HTTP_UNAUTHORIZED");
                return recvFullRequestAndResponse401Unauthorized();
            }
            
            _transformer = _target.getHttpRequestTransformerOf(_requestData.request());
            
            _memo.beginBizStep(STEP.ROUTING);
            _memo.endBizStep(STEP.ROUTING, _watch4Step.stopAndRestart());
            
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("dispatch to ({}) for request({})", serviceUri(), _requestData);
            }
            
            _memo.beginBizStep(STEP.OBTAINING_HTTPCLIENT);
            
            _httpClientWrapper.startObtainHttpClient(
                    _target.getGuideBuilder(),
                    genGuideReactor(),
                    new Guide.DefaultRequirement()
                        .uri(_target.serviceUri())
                        .priority(0)
                );
            return OBTAINING;
        }
    }
    .handler(handlersOf(CACHE_HTTPCONTENT))
    .handler(handlersOf(new ONDETACH(new Runnable() {
        @Override
        public void run() {
            _memo.incBizResult(RESULT.SOURCE_CANCELED, -1);
            LOG.warn("SOURCE_CANCELED\ncost:[{}]s\nrequest:[{}]\ndispatch to:[{}]",
                    -1, _requestData, serviceUri());
            setEndReason("relay.SOURCE_CANCELED");
        }})))
    .freeze();

    private BizStep recvFullRequestAndResponse401Unauthorized() {
        addFlowLifecycleListener(memoUnauthorizedAction);
        final BizStep step = BizStepBuilder.waitforRequestFinishedAndResponse401Unauthorized
                .build(_requestData, _channelCtx);
        return (null != step)
            ? step.handler(handlersOf(new ONDETACH(new Runnable() {
                    @Override
                    public void run() {
                        removeFlowLifecycleListener(memoUnauthorizedAction);
                        memoDetachResult();
                    }})))
                .freeze()
            : null
            ;
    }
    
    private BizStep recvFullRequestAndResponse200OK() {
        final BizStep step = BizStepBuilder.waitforRequestFinishedAndResponse200OK
                .build(this._requestData, this._channelCtx);
        return (null != step)
            ? step.handler(handlersOf(new ONDETACH(new Runnable() {
                    @Override
                    public void run() {
                        memoDetachResult();
                    }})))
                .freeze()
            : null
            ;
    }
    
    private final BizStep OBTAINING = new BizStep("relay.OBTAINING") {
        @OnEvent(event = "onHttpClientObtained")
        private BizStep onHttpObtained(
                final int guideId,
                final HttpClient httpclient) {
            if (!isValidGuideId(guideId)) {
                return BizStep.CURRENT_BIZSTEP;
            }

            _memo.endBizStep(STEP.OBTAINING_HTTPCLIENT, _watch4Step.stopAndRestart());
            _memo.beginBizStep(STEP.TRANSFER_CONTENT);
            
            _httpClientWrapper.setHttpClient(httpclient);
            
            if (null!=_transformer) {
            	return recvFullContentAndDoTransformThenGoto(RECVCONTENT_TRANSFORMREQ, RECVRESP);
            }
            else {
	            transferHttpRequestAndContents();
	            return recvAndTransferAllContentsThenGoto(TRANSFERCONTENT, RECVRESP);
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

	private BizStep recvFullContentAndDoTransformThenGoto(
			final BizStep stepOfRecvFullContent,
			final BizStep stepOfNext) {
        if (this._requestData.isRequestFully()) {
    		if (transformAndReplaceHttpRequest()) {
                //  add transform request count and record from relay begin 
                //  until transform complete 's time cost 
                this._memo.incBizResult(RESULT.TRANSFORM_REQUEST, 
                        this._watch4Result.pauseAndContinue());
    		}
    		transferHttpRequestAndContents();
    		this._memo.endBizStep(STEP.TRANSFER_CONTENT, 
    				this._watch4Step.stopAndRestart());
    		this._memo.beginBizStep(STEP.RECV_RESP);
    		return stepOfNext;
        }
        else {
            return stepOfRecvFullContent;
        }
	}
    
    private boolean transformAndReplaceHttpRequest() {
        final FullHttpRequest newRequest = transformToFullHttpRequest();
        if (null!=newRequest) {
            try {
                this._requestData.clear();
                this._requestData.setHttpRequest(newRequest);
                return true;
            } finally {
                newRequest.release();
            }
        }
        else {
            return false;
        }
    }
    
    private FullHttpRequest transformToFullHttpRequest() {
        if (null!=this._transformer) {
            final HttpRequest req = this._requestData.request();
            final ByteBuf content = this._requestData.retainFullContent();
            try {
                return this._transformer.transform(req, content);
            } finally {
                content.release();
                this._transformer = null;
            }
        }
        else {
            return null;
        }
    }
    
    private final BizStep RECVCONTENT_TRANSFORMREQ = new BizStep("relay.RECVCONTENT_AND_TRANSFORMREQ") {
        @OnEvent(event = "onHttpContent")
        private BizStep recvHttpContentAndTransformRequest(final HttpContent httpContent) {
            _requestData.addContent(httpContent);
            return recvFullContentAndDoTransformThenGoto(BizStep.CURRENT_BIZSTEP, RECVRESP);
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
    
	private BizStep recvAndTransferAllContentsThenGoto(
			final BizStep stepOfRecvAllContents,
			final BizStep stepOfNext) {
        if (this._requestData.isRequestFully()) {
    		this._memo.endBizStep(STEP.TRANSFER_CONTENT, 
    				this._watch4Step.stopAndRestart());
    		this._memo.beginBizStep(STEP.RECV_RESP);
    		return stepOfNext;
        }
        else {
            return stepOfRecvAllContents;
        }
	}
	
    private final BizStep TRANSFERCONTENT = new BizStep("relay.TRANSFERCONTENT") {
        @OnEvent(event = "onHttpContent")
        private BizStep recvHttpContentAndTransfer(final HttpContent httpContent) {
            _requestData.addContent(httpContent);
            transferHttpContent(httpContent);
            return recvAndTransferAllContentsThenGoto(BizStep.CURRENT_BIZSTEP, RECVRESP);
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
                return BizStep.CURRENT_BIZSTEP;
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("channel for {} recv response {}", serviceUri(), response);
            }
            
            if ( _target.isCheckResponseStatus() ) {
                if (isHttpClientError(response)) {
                    return retryFor(RESULT.HTTP_CLIENT_ERROR,response);
                }
                
                if (isHttpServerError(response)) {
                    return retryFor(RESULT.HTTP_SERVER_ERROR,response);
                }
            }
            
            //  http response will never be changed, so record it
            _httpResponse = ReferenceCountUtil.retain(response);
            _channelCtx.write(ReferenceCountUtil.retain(response));
            
            //  TODO consider 响应为1xx，204，304相应或者head请求，则直接忽视掉消息实体内容。
            //  当满足上述情况时，是否应该直接结束转发流程。
            return buildRecvContentByConnectionEntity();
        }

        private BizStep retryFor(final RESULT result, final HttpResponse response) throws Exception {
            _httpClientWrapper.detachHttpClient();
            _memo.endBizStep(STEP.RECV_RESP, _watch4Step.stopAndRestart());
            final long ttl = _watch4Result.stopAndRestart();
            _memo.incBizResult(result, ttl);
            LOG.warn("{},retry\ncost:[{}]s\nrequest:[{}]\ndispatch to:[{}]\nresponse:[{}]",
                    result.name(), ttl / (float)1000.0, _requestData, serviceUri(), response);
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
                return BizStep.CURRENT_BIZSTEP;
            }
            
            if (LOG.isDebugEnabled()) {
                LOG.debug("channel for {} recv http content {}", serviceUri(), content);
            }
            
            // content 的内容仅保证在事件 onHttpContentReceived 处理方法中有效
            // 而channelCtx.write完成后，会主动调用 ReferenceCountUtil.release 释放content
            // 因此需要先使用 ReferenceCountUtil.retain 增加一次引用计数
            _channelCtx.write(
                ReferenceCountUtil.retain(content));
            return BizStep.CURRENT_BIZSTEP;
        }

        @OnEvent(event = "onLastHttpContentReceived")
        private BizStep lastContentReceived(final int httpClientId,
                final LastHttpContent content) throws Exception {
            if (!isValidHttpClientId(httpClientId)) {
                return BizStep.CURRENT_BIZSTEP;
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("channel for {} recv last http content {}", serviceUri(), content);
            }
            
            _memo.endBizStep(STEP.RECV_RESP, _watch4Step.stopAndRestart());
            
            //  release relay's http client
            _httpClientWrapper.detachHttpClient();
            
            // content 的内容仅保证在事件 onLastHttpContentReceived 处理方法中有效
            // 而channelCtx.writeAndFlush完成后，会主动调用 ReferenceCountUtil.release 释放content
            // 因此需要先使用 ReferenceCountUtil.retain 增加一次引用计数
            final ChannelFuture future = _channelCtx.writeAndFlush(ReferenceCountUtil.retain(content));
            if ( !HttpHeaders.isKeepAlive(_requestData.request()) ) {
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
        if ( HttpHeaders.isKeepAlive(_requestData.request()) ) {
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
                                        serviceUri());
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

    @SuppressWarnings("unchecked")
    private GuideReactor<Integer> genGuideReactor() {
        return (GuideReactor<Integer>) this
                .queryInterfaceInstance(GuideReactor.class);
    }

    private boolean isValidGuideId(final int guideId) {
        final boolean ret = this._httpClientWrapper.validateGuideId(guideId);
        if (!ret) {
            if (LOG.isDebugEnabled()) {
                LOG.debug(
                        "RelayFlow({})/{}/{}: special guide id({}) is !MISMATCH! current httpclientWrapper ({}), just ignore.",
                        this, currentEventHandler().getName(), currentEvent(),
                        guideId, this._httpClientWrapper);
            }
        }
        return ret;
    }

    private boolean isValidHttpClientId(final int httpClientId) {
        final boolean ret = this._httpClientWrapper.validateHttpClientId(httpClientId);
        if (!ret) {
            if (LOG.isDebugEnabled()) {
                LOG.debug(
                        "RelayFlow({})/{}/{}: special httpclient id({}) is !MISMATCH! current httpclientWrapper ({}), just ignore.",
                        this, currentEventHandler().getName(), currentEvent(),
                        httpClientId, this._httpClientWrapper);
            }
        }
        return ret;
    }

    private BizStep launchRetry(final long ttl) throws Exception {
        _memo.incBizResult(RESULT.RELAY_RETRY, ttl);
        selfEventReceiver().acceptEvent("startRelay");
        return WAIT;
    }

    private static boolean isHttpClientError(final HttpResponse response) {
        return response.getStatus().code() >= 400 
            && response.getStatus().code() < 500;
    }

    private static boolean isHttpServerError(final HttpResponse response) {
        return response.getStatus().code() >= 500 
            && response.getStatus().code() < 600;
    }
    
    /**
     * @param httpclient
     */
    private void transferHttpRequest() {
        this._requestData.request().setUri(
            this._target.rewritePath(this._requestData.request().getUri()));
        try {
            if (LOG.isDebugEnabled()) {
                LOG.debug("send http request {}", _requestData);
            }
            this._httpClientWrapper.sendHttpRequest(
                this._requestData.request(), genHttpReactor());
        }
        catch (Exception e) {
            LOG.error(
                    "state({})/{}: exception when sendHttpRequest, detail:{}",
                    currentEventHandler().getName(), currentEvent(),
                    ExceptionUtils.exception2detail(e));
        }
    }

    private void transferHttpContent(final HttpContent content) {
        try {
            this._httpClientWrapper.sendHttpContent(content);
        }
        catch (Exception e) {
            LOG.error(
                    "state({})/{}: exception when sendHttpContent, detail:{}",
                    currentEventHandler().getName(), currentEvent(),
                    ExceptionUtils.exception2detail(e));
        }
    }

    private void transferHttpRequestAndContents() {
        transferHttpRequest();
        this._requestData.foreachContent(new Visitor<HttpContent>() {
            @Override
            public void visit(final HttpContent content) throws Exception {
                transferHttpContent(content);
            }});
    }

    /**
     * @return
     */
    private String serviceUri() {
        return null != this._target ? this._target.serviceUri().toString() : "non-uri";
    }

    private void memoDetachResult() {
        final long ttl = _watch4Result.stopAndRestart();
        _memo.incBizResult(RESULT.SOURCE_CANCELED, ttl);
        LOG.warn("SOURCE_CANCELED\ncost:[{}]s\nrequest:[{}]\ndispatch to:[{}]",
                ttl / (float)1000.0, _requestData, serviceUri());
        setEndReason("relay.SOURCE_CANCELED");
    }

    private long memoHttplostResult(final RESULT result, final boolean isRetry) {
        final long ttl = _watch4Result.stopAndRestart();
        _memo.incBizResult(result, ttl);
        LOG.warn("{},{}\ncost:[{}]s\nrequest:[{}]\ndispatch to:[{}]",
                result.name(), (isRetry ? "retry" : "not retry"), 
                ttl / (float)1000.0, _requestData, serviceUri());
        return ttl;
    }

    private void memoRelaySuccessResult(final String successName) {
        final long ttl = _watch4Result.stopAndRestart();
        _memo.incBizResult(RESULT.RELAY_SUCCESS, ttl);
        if (this._target.isShowInfoLog()) {
            LOG.info("{}\ncost:[{}]s\nrequest:[{}]\ndispatch to:[{}]\nresponse:[{}]",
                    successName, ttl / (float)1000.0, _requestData, serviceUri(), _httpResponse);
        }
    }

    private void destructor() throws Exception {
        this._requestData.clear();
        ReferenceCountUtil.safeRelease(this._httpResponse);
        //  replace logger to nop logger to disable all log message after this destroy
        this._proxyLogger.setImpl(NOPLogger.NOP_LOGGER);
    }

    private static final FlowLifecycleListener<RelayFlow> LIFECYCLE_LISTENER = 
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
            flow.destructor();
        }
    };
    
    private final RelayMemo.Builder _memoBuilder;
    private final RoutingInfoMemo _noRoutingMemo;
    private final Router<HttpRequest, Dispatcher> _router;
    private HttpRequestTransformer _transformer = null;
    
    private final HttpRequestData _requestData = new HttpRequestData();
    private final HttpClientWrapper _httpClientWrapper = new HttpClientWrapper();

    private ChannelHandlerContext _channelCtx;
    private HttpResponse _httpResponse = null;
    
    private Target _target;
    private RelayMemo _memo;

    private final StopWatch _watch4Step = new StopWatch();
    private final StopWatch _watch4Result = new StopWatch();
}
