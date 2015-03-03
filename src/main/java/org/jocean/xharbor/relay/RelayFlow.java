/**
 * 
 */
package org.jocean.xharbor.relay;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

import java.util.HashMap;
import java.util.Map;

import org.jocean.event.api.AbstractFlow;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.FlowLifecycleListener;
import org.jocean.event.api.FlowStateChangedListener;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.httpclient.api.Guide;
import org.jocean.httpclient.api.Guide.GuideReactor;
import org.jocean.httpclient.api.HttpClient;
import org.jocean.httpclient.api.HttpClient.HttpReactor;
import org.jocean.httpclient.impl.HttpUtils;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.ProxyBuilder;
import org.jocean.idiom.Slf4jLoggerSource;
import org.jocean.idiom.StopWatch;
import org.jocean.idiom.Visitor;
import org.jocean.idiom.stats.BizMemo;
import org.jocean.idiom.stats.BizMemo.StepMemo;
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

    private static final FlowLifecycleListener<RelayFlow> LIFECYCLE_LISTENER = 
            new FlowLifecycleListener<RelayFlow>() {
        @Override
        public void afterEventReceiverCreated(
                final RelayFlow flow, final EventReceiver receiver)
                throws Exception {
            receiver.acceptEvent("init");
        }
        @Override
        public void afterFlowDestroy(final RelayFlow flow)
                throws Exception {
            flow.destructor();
        }
    };
    
    private void whenStateChanged(
    		final BizStep prev,
			final BizStep next, 
			final String causeEvent, 
			final Object[] causeArgs)
			throws Exception {
		LOG.debug("onStateChanged: prev:{} next:{} event:{}", prev, next, causeEvent);
		if ( RECVRESP == next) {
			_stepmemo.beginBizStep(STEP.RECV_RESP);
		}
		if (null==next && "detach".equals(causeEvent)) {
			// means flow end by detach event
			if (null!=_stepmemo) {
				_stepmemo.endBizStep(-1);
			}
			memoDetachResult();
		}
	}
    
    private static final FlowStateChangedListener<RelayFlow, BizStep> STATECHANGED_LISTENER = 
		new FlowStateChangedListener<RelayFlow, BizStep>() {
		@Override
		public void onStateChanged(
				final RelayFlow flow, 
				final BizStep prev,
				final BizStep next, 
				final String causeEvent, 
				final Object[] causeArgs)
				throws Exception {
			flow.whenStateChanged(prev, next, causeEvent, causeArgs);
		}
	};
    
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
        addFlowStateChangedListener(STATECHANGED_LISTENER);
    }
    
    RelayFlow attach(
            final ChannelHandlerContext channelCtx,
            final HttpRequest httpRequest) {
        this._requestData.setHttpRequest(httpRequest);
        this._channelCtx = channelCtx;
        return this;
    }

    private final Object ONDETACH = new Object() {
        @OnEvent(event = "detach")
        private BizStep onDetach() throws Exception {
            if (LOG.isDebugEnabled()) {
                LOG.debug("relay for uri:{} progress canceled", serviceUri());
            }
            _httpClientWrapper.detachHttpClient();
            try {
                _channelCtx.close();
            }
            catch(Throwable e) {
                LOG.warn("exception when close {}, detail: {}", 
                        _channelCtx, ExceptionUtils.exception2detail(e));
            }
            return null;
        }
    };

    private final class RETRY_WHENHTTPLOST {
    	RETRY_WHENHTTPLOST(final RESULT result) {
    		this._result = result;
    	}
        @OnEvent(event = "onHttpClientLost")
        private BizStep onHttpLost(final int guideId)
                throws Exception {
            if (!_httpClientWrapper.validateGuideId(guideId)) {
                return BizStep.CURRENT_BIZSTEP;
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("{}/{}: http for {} lost.", 
                        currentEventHandler().getName(), currentEvent(), 
                        serviceUri());
            }
            _stepmemo.endBizStep(-1);
            _memo.incBizResult(RESULT.RELAY_RETRY, memoHttplostResult(_result, true));
            selfEventReceiver().acceptEvent("init");
            return INIT;
        }
        
        private final RESULT _result;
    };
    
    public final BizStep INIT = new BizStep("relay.INIT") {
        @SuppressWarnings("unchecked")
		@OnEvent(event = "init")
        private BizStep doRouting() {

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
            _stepmemo = BizMemo.Util.buildStepMemo(_memo, _watch4Step);
            		
            if (_target.isNeedAuthorization(_requestData.request())) {
                setEndReason("relay.HTTP_UNAUTHORIZED");
                return recvFullRequestAndResponse401Unauthorized();
            }
            
            _transformer = _target.getHttpRequestTransformerOf(_requestData.request());
            
            _stepmemo.beginBizStep(STEP.ROUTING);
            
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("dispatch to ({}) for request({})", serviceUri(), _requestData);
            }
            
            _stepmemo.beginBizStep(STEP.OBTAINING_HTTPCLIENT);
            
            _httpClientWrapper.startObtainHttpClient(
                    _target.getGuideBuilder(),
                    queryInterfaceInstance(GuideReactor.class),
                    new Guide.DefaultRequirement()
                        .uri(_target.serviceUri())
                        .priority(0)
                );
            return BizStep.CURRENT_BIZSTEP;
        }
        
        @OnEvent(event = "onHttpClientObtained")
        private BizStep obtainHttpClient(
                final int guideId,
                final HttpClient httpclient) {
            if (!_httpClientWrapper.validateGuideId(guideId)) {
                return BizStep.CURRENT_BIZSTEP;
            }

            _stepmemo.beginBizStep(STEP.TRANSFER_CONTENT);
            _httpClientWrapper.setHttpClient(httpclient);
            if (null==_transformer) {
	            transferHttpRequestAndContents();
            	return _requestData.recvFullContentThenGoto(
            			"relay.TRANSFERCONTENT",
            			new Visitor<HttpContent>() {
							@Override
							public void visit(final HttpContent httpContent) throws Exception {
		            			transferHttpContent(httpContent);
							}},
            			null,
            			RECVRESP,
            			new RETRY_WHENHTTPLOST(RESULT.RELAY_FAILURE),
            			ONDETACH);
            }
            else {
            	return _requestData.recvFullContentThenGoto(
            			"relay.RECVCONTENT_TRANSFORMREQ",
            			null,
            			new Runnable() {
            				@Override
            				public void run() {
            		    		if (_requestData.transformAndReplace(_transformer)) {
            		                //  add transform request count and record from relay begin 
            		                //  until transform complete 's time cost 
            		                _memo.incBizResult(RESULT.TRANSFORM_REQUEST, 
            		                        _watch4Result.pauseAndContinue());
            		    		}
            		    		_transformer = null;
            		    		transferHttpRequestAndContents();
            				}},
            			RECVRESP,
            			new RETRY_WHENHTTPLOST(RESULT.RELAY_FAILURE),
            			ONDETACH);
            }
        }
        
        @OnEvent(event = "onHttpContent")
        private BizStep cacheHttpContent(final HttpContent httpContent) {
            _requestData.addContent(httpContent);
            return BizStep.CURRENT_BIZSTEP;
        }

        private BizStep recvFullRequestAndResponse401Unauthorized() {
        	return _requestData.recvFullContentThenGoto(
        			"relay.RESP_401",
        			null,
        			new Runnable() {
						@Override
						public void run() {
							_memo.incBizResult(RESULT.HTTP_UNAUTHORIZED, _watch4Result.stopAndRestart());
							_requestData.response401Unauthorized("Basic realm=\"iplusmed\"", _channelCtx);
						}},
        			null,
        			ONDETACH);
        }
        
        private BizStep recvFullRequestAndResponse200OK() {
        	return _requestData.recvFullContentThenGoto(
        			"relay.RESP_200OK",
        			null,
        			new Runnable() {
						@Override
						public void run() {
							_requestData.response200OK(_channelCtx);
						}},
        			null,
        			ONDETACH);
        }
    }
    .handler(handlersOf(new RETRY_WHENHTTPLOST(RESULT.CONNECTDESTINATION_FAILURE)))
    .handler(handlersOf(ONDETACH))
    .freeze();

    private final BizStep RECVRESP = new BizStep("relay.RECVRESP") {
    	
        private boolean isHttpClientError(final HttpResponse response) {
            return response.getStatus().code() >= 400 
                && response.getStatus().code() < 500;
        }

        private boolean isHttpServerError(final HttpResponse response) {
            return response.getStatus().code() >= 500 
                && response.getStatus().code() < 600;
        }
        
        @OnEvent(event = "onHttpResponseReceived")
        private BizStep responseReceived(final int httpClientId,
                final HttpResponse response) throws Exception {
            if (!_httpClientWrapper.validateHttpClientId(httpClientId)) {
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
            
            if (HttpUtils.isHttpResponseHasMoreContent(response)) {
	            return recvResponseContent(response);
            }
            else {
                return sendbackResponseAndFinishRelay(response);
            }
        }

		private BizStep sendbackResponseAndFinishRelay(
				final HttpResponse response) {
			//  当响应为1xx，204，304相应或者head请求，则直接忽视掉消息实体内容。
			//  当满足上述情况时，是否应该直接结束转发流程。
			if (LOG.isDebugEnabled()) {
			    LOG.debug("channel for {} has no more content, just finish relay.", serviceUri());
			}
			
			_stepmemo.endBizStep();
			//  release relay's http client
			_httpClientWrapper.detachHttpClient();
			
			final ChannelFuture future = _channelCtx.writeAndFlush(ReferenceCountUtil.retain(response));
			if ( !HttpHeaders.isKeepAlive(_requestData.request()) ) {
			    future.addListener(ChannelFutureListener.CLOSE);
			}
			memoRelaySuccessResult("RELAY_SUCCESS.NOMORE_CONTENT");
			setEndReason("relay.RELAY_SUCCESS.NOMORE_CONTENT");
			return null;
		}

		private BizStep recvResponseContent(final HttpResponse response) {
			//  http response will never be changed, so record it
			_httpResponse = ReferenceCountUtil.retain(response);
			_channelCtx.write(ReferenceCountUtil.retain(response));
			
			//  ref : http://blog.csdn.net/yankai0219/article/details/8269922
			// 1、在http1.1及之后版本。如果是keep alive，则content-length和chunk必然是二选一。
			//   若是非keep alive(Connection: close)，则和http1.0一样, Server侧通过 socket 关闭来表示消息结束
			// 2、在Http 1.0及之前版本中，content-length字段可有可无。Server侧通过 socket 关闭来表示消息结束
			return HttpHeaders.isKeepAlive(_requestData.request())
					? RECVCONTENT_KEEPALIVE
			        // Connection: close
					: RECVCONTENT_CLOSE
					;
		}

        private BizStep retryFor(final RESULT result, final HttpResponse response) throws Exception {
            _httpClientWrapper.detachHttpClient();
            _stepmemo.endBizStep();
            final long ttl = _watch4Result.stopAndRestart();
            _memo.incBizResult(result, ttl);
            LOG.warn("{},retry\ncost:[{}]s\nrequest:[{}]\ndispatch to:[{}]\nresponse:[{}]",
                    result.name(), ttl / (float)1000.0, _requestData, serviceUri(), response);
            return launchRetry(ttl);
        }
        
        private BizStep launchRetry(final long ttl) throws Exception {
            _memo.incBizResult(RESULT.RELAY_RETRY, ttl);
            selfEventReceiver().acceptEvent("init");
            return INIT;
        }
    }
    .handler(handlersOf(new RETRY_WHENHTTPLOST(RESULT.RELAY_FAILURE)))
    .handler(handlersOf(ONDETACH))
    .freeze();

    private final Object RECVCONTENT = new Object() {
        @OnEvent(event = "onHttpContentReceived")
        private BizStep contentReceived(final int httpClientId,
                final HttpContent content) throws Exception {
            if (!_httpClientWrapper.validateHttpClientId(httpClientId)) {
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
            if (!_httpClientWrapper.validateHttpClientId(httpClientId)) {
                return BizStep.CURRENT_BIZSTEP;
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("channel for {} recv last http content {}", serviceUri(), content);
            }
            
            _stepmemo.endBizStep();
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
    };

    private final BizStep RECVCONTENT_KEEPALIVE = new BizStep("relay.RECVCONTENT.KeepAlive") {
        @OnEvent(event = "onHttpClientLost")
        private BizStep onHttpLost(final int guideId)
                throws Exception {
            if (!_httpClientWrapper.validateGuideId(guideId)) {
                return BizStep.CURRENT_BIZSTEP;
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("{}/{}: http for {} lost.", 
                        currentEventHandler().getName(), currentEvent(), 
                        serviceUri());
            }
            try {
                _channelCtx.close();
            }
            catch(Throwable e) {
                LOG.warn("exception when close {}, detail: {}", 
                        _channelCtx, ExceptionUtils.exception2detail(e));
            }
            _stepmemo.endBizStep(-1);
            memoHttplostResult(RESULT.RELAY_FAILURE, false);
            setEndReason("relay.RELAY_FAILURE");
            return null;
        }
    }
    .handler(handlersOf(RECVCONTENT))
    .handler(handlersOf(ONDETACH))
    .freeze();
    
    private final BizStep RECVCONTENT_CLOSE = new BizStep("relay.RECVCONTENT.Close") {
        @OnEvent(event = "onHttpClientLost")
        private BizStep onHttpLost(final int guideId)
                throws Exception {
            if (!_httpClientWrapper.validateGuideId(guideId)) {
                return BizStep.CURRENT_BIZSTEP;
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("channel for {} is Connection: close, so just mark RELAY_SUCCESS and close peer.", 
                        serviceUri());
            }
            _stepmemo.endBizStep();
            _channelCtx.flush().close();
            memoRelaySuccessResult("HTTP10.RELAY_SUCCESS");
            setEndReason("relay.HTTP10.RELAY_SUCCESS");
            return null;
        }
    }
    .handler(handlersOf(RECVCONTENT))
    .handler(handlersOf(ONDETACH))
    .freeze();
    
    @SuppressWarnings("unchecked")
	private void transferHttpRequest() {
        this._requestData.request().setUri(
            this._target.rewritePath(this._requestData.request().getUri()));
        try {
            if (LOG.isDebugEnabled()) {
                LOG.debug("send http request {}", _requestData);
            }
            this._httpClientWrapper.sendHttpRequest(
                this._requestData.request(), 
                this.queryInterfaceInstance(HttpReactor.class));
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

    private String serviceUri() {
        return null != this._target ? this._target.serviceUri().toString() : "non-uri";
    }

    private void memoDetachResult() {
        final long ttl = _watch4Result.stopAndRestart();
        if (null!=_memo) {
        	_memo.incBizResult(RESULT.SOURCE_CANCELED, ttl);
        }
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
    private StepMemo<STEP> _stepmemo;

    private final StopWatch _watch4Step = new StopWatch();
    private final StopWatch _watch4Result = new StopWatch();
}
