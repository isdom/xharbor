package org.jocean.xharbor.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.atomic.AtomicReference;

import org.jmock.Expectations;
import org.jmock.Mockery;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventEngine;
import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.EventUtils;
import org.jocean.event.api.FlowStateChangedListener;
import org.jocean.event.core.FlowContainer;
import org.jocean.http.HttpRequestTransformer;
import org.jocean.httpclient.api.Guide;
import org.jocean.httpclient.api.Guide.GuideReactor;
import org.jocean.httpclient.api.Guide.Requirement;
import org.jocean.httpclient.api.GuideBuilder;
import org.jocean.httpclient.api.HttpClient;
import org.jocean.httpclient.api.HttpClient.HttpReactor;
import org.jocean.httpserver.ServerAgent.ServerTask;
import org.jocean.idiom.ExectionLoop;
import org.jocean.xharbor.api.Dispatcher;
import org.jocean.xharbor.api.RelayMemo;
import org.jocean.xharbor.api.RelayMemo.RESULT;
import org.jocean.xharbor.api.RelayMemo.STEP;
import org.jocean.xharbor.api.Router;
import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.api.RoutingInfoMemo;
import org.jocean.xharbor.api.Target;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RelayFlowTestCase {

    private static final Logger LOG = LoggerFactory
            .getLogger(RelayFlowTestCase.class);
    
	Object _guideCtx;
	Guide.GuideReactor<Object> _reactor;
	boolean _isReturnHttpClient = true;
	
	final GuideBuilder guideBuilder = new GuideBuilder() {
		@Override
		public Guide createHttpClientGuide() {
			return new Guide() {
				@Override
				public void detach() throws Exception {
				}
				@Override
				public <CTX> void obtainHttpClient(CTX ctx,
						GuideReactor<CTX> reactor, Requirement requirement) {
					LOG.debug("call obtainHttpClient");
					
					_guideCtx = ctx;
					_reactor = (GuideReactor<Object>)reactor;
					if (_isReturnHttpClient) {
						try {
							reactor.onHttpClientObtained(ctx, new HttpClient() {
	
								@Override
								public <CTX> void sendHttpRequest(
										final CTX ctx,
										final HttpRequest request,
										final HttpReactor<CTX> reactor) throws Exception {
									LOG.debug("call sendHttpRequest:{}", request);
								}
	
								@Override
								public void sendHttpContent(HttpContent content)
										throws Exception {
									LOG.debug("call sendHttpContent:{}", content);
								}});
						} catch (Exception e) {
						}
					}
				}};
		}};
		
    final Target mockTarget = new Target() {

		@Override
		public GuideBuilder getGuideBuilder() {
			return guideBuilder;
		}

		@Override
		public URI serviceUri() {
			try {
				return new URI("http://127.0.0.1");
			} catch (URISyntaxException e) {
				return null;
			}
		}

		@Override
		public String rewritePath(String path) {
			return path;
		}

		@Override
		public int addWeight(int deltaWeight) {
			return 0;
		}

		@Override
		public void markServiceDownStatus(boolean isDown) {
		}

		@Override
		public void markAPIDownStatus(boolean isDown) {
		}

		@Override
		public boolean isNeedAuthorization(HttpRequest httpRequest) {
			return false;
		}

		@Override
		public boolean isCheckResponseStatus() {
			return false;
		}

		@Override
		public boolean isShowInfoLog() {
			return false;
		}

		@Override
		public HttpRequestTransformer getHttpRequestTransformerOf(
				HttpRequest httpRequest) {
			return null;
		}};
    
    final Dispatcher mockDispatcher = new Dispatcher() {

		@Override
		public Target dispatch() {
			return mockTarget;
		}

		@Override
		public boolean IsValid() {
			return true;
		}};
		
    final Router<HttpRequest, Dispatcher> router = new Router<HttpRequest, Dispatcher>() {

		@Override
		public Dispatcher calculateRoute(
				final HttpRequest input,
				final Router.Context context) {
			return mockDispatcher;
		}};
		
	RelayMemo.STEP lastStep;
	RelayMemo.RESULT lastResult;
	
    final RelayMemo.Builder memoBuilder = new RelayMemo.Builder() {

		@Override
		public RelayMemo build(final Target target, final RoutingInfo info) {
			return new RelayMemo() {

				@Override
				public void beginBizStep(STEP step) {
					LOG.debug("call beginBizStep:{}", step);
					assertNull(lastStep);
					lastStep = step;
				}

				@Override
				public void endBizStep(STEP step, long ttl) {
					LOG.debug("call endBizStep:{}/{}", step, ttl);
					if (step.equals(lastStep)) {
						lastStep = null;
					}
					else {
						fail("endBizStep:" + step + " not eqauls lastStep:" + lastStep);
					}
				}

				@Override
				public void incBizResult(RESULT result, long ttl) {
					LOG.debug("call incBizResult:{}/{}", result, ttl);
					lastResult = result;
				}};
		}};
		
	final RoutingInfoMemo noRoutingMemo = new RoutingInfoMemo() {
		@Override
		public void incRoutingInfo(final RoutingInfo info) {
			LOG.debug("call incRoutingInfo:{}/{}", info);
		}};
	
	final Mockery jmock = new Mockery();
	final EventEngine engine = 
			new FlowContainer("test").buildEventEngine(ExectionLoop.immediateLoop);
		
	@Test
	public void testSourceCanceled() throws Exception {
		final Guide 		guide = jmock.mock(Guide.class);
		final GuideBuilder 	guideBuilder = jmock.mock(GuideBuilder.class);
		final Target 		target = jmock.mock(Target.class);
		final Dispatcher 	dispatcher = jmock.mock(Dispatcher.class);
		final Router<HttpRequest, Dispatcher> router = 
				(Router<HttpRequest, Dispatcher>)jmock.mock(Router.class);
		final RelayMemo 	memo = jmock.mock(RelayMemo.class);
		final RelayMemo.Builder	memoBuilder = jmock.mock(RelayMemo.Builder.class);
		
		final ChannelHandlerContext channelCtx = jmock.mock(ChannelHandlerContext.class);
		
		jmock.checking(new Expectations() {   
	        {   
	        	allowing(guide).obtainHttpClient(
	        			with(anything()), 
	        			with(any(GuideReactor.class)), 
	        			with(any(Requirement.class)));
	        	
	        	allowing(guide).detach();
	        	
	        	allowing(guideBuilder).createHttpClientGuide();
	            will(returnValue(guide));
	            
	        	allowing(target).getGuideBuilder();
	            will(returnValue(guideBuilder));
	            
	        	allowing(target).serviceUri();
	            will(returnValue(new URI("http://127.0.0.1")));
	            
	        	allowing(target).isNeedAuthorization(with(any(HttpRequest.class)));
	            will(returnValue(false));
	            
	        	allowing(target).getHttpRequestTransformerOf(with(any(HttpRequest.class)));
	            will(returnValue(null));
	            
	        	allowing(dispatcher).dispatch();
	            will(returnValue(target));
	            
	        	allowing(router).calculateRoute(with(any(HttpRequest.class)), 
	        			with(any(org.jocean.xharbor.api.Router.Context.class)));
	            will(returnValue(dispatcher));
	            
	            allowing(memo).beginBizStep(with(any(STEP.class)));
	            allowing(memo).endBizStep(with(any(STEP.class)), with(any(long.class)));
	            
	            oneOf(memo).incBizResult(with(equal(RESULT.SOURCE_CANCELED)), with(any(long.class)));
	            
	            allowing(memoBuilder).build(with(any(Target.class)), (RoutingInfo)with(anything()));
	            will(returnValue(memo));
	            
	            allowing(channelCtx).channel();
	            will(returnValue(null));
	            
	            allowing(channelCtx).close();
	        }   
	    });
		
		// execute
		final RelayFlow relay = new RelayFlow(router, memoBuilder, null)
			.attach(channelCtx, new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/test"));
		final EventReceiver receiver =  engine.create("testcase", relay.INIT, relay);
		
		final ServerTask task = EventUtils.buildInterfaceAdapter(ServerTask.class, receiver);
		task.detach();
		
		// verify
        jmock.assertIsSatisfied();
	}

	@Test
	public void testRecvResp() throws Exception {
		final HttpClient	httpclient = jmock.mock(HttpClient.class);
//		final Guide 		guide = jmock.mock(Guide.class);
		final GuideBuilder 	guideBuilder = jmock.mock(GuideBuilder.class);
		final Target 		target = jmock.mock(Target.class);
		final Dispatcher 	dispatcher = jmock.mock(Dispatcher.class);
		final Router<HttpRequest, Dispatcher> router = 
				(Router<HttpRequest, Dispatcher>)jmock.mock(Router.class);
		final RelayMemo 	memo = jmock.mock(RelayMemo.class);
		final RelayMemo.Builder	memoBuilder = jmock.mock(RelayMemo.Builder.class);
		
		final ChannelHandlerContext channelCtx = jmock.mock(ChannelHandlerContext.class);
		
		final Guide 		guide = new Guide() {
			@Override
			public void detach() throws Exception {
			}
			@Override
			public <CTX> void obtainHttpClient(CTX ctx,
					GuideReactor<CTX> reactor, Requirement requirement) {
				try {
					reactor.onHttpClientObtained(ctx, httpclient);
				} catch (Exception e) {
				}
			}};
		jmock.checking(new Expectations() {   
	        {   
	        	allowing(httpclient).sendHttpRequest(
	        			with(anything()), 
	        			with(any(HttpRequest.class)), 
	        			with(any(HttpReactor.class)));
	        	
	        	allowing(httpclient).sendHttpContent(
	        			with(any(HttpContent.class)));
	        	
//	        	allowing(guide).obtainHttpClient(
//	        			with(anything()), 
//	        			with(any(GuideReactor.class)), 
//	        			with(any(Requirement.class)));
//	        	
//	        	allowing(guide).detach();
	        	
	        	allowing(guideBuilder).createHttpClientGuide();
	            will(returnValue(guide));
	            
	        	allowing(target).getGuideBuilder();
	            will(returnValue(guideBuilder));
	            
	        	allowing(target).serviceUri();
	            will(returnValue(new URI("http://127.0.0.1")));
	            
	        	allowing(target).isNeedAuthorization(with(any(HttpRequest.class)));
	            will(returnValue(false));
	            
	        	allowing(target).getHttpRequestTransformerOf(with(any(HttpRequest.class)));
	            will(returnValue(null));
	            
	        	allowing(target).rewritePath(with(any(String.class)));
	            will(returnValue("/test"));
	            
	        	allowing(dispatcher).dispatch();
	            will(returnValue(target));
	            
	        	allowing(router).calculateRoute(with(any(HttpRequest.class)), 
	        			with(any(org.jocean.xharbor.api.Router.Context.class)));
	            will(returnValue(dispatcher));
	            
	            ignoring(memo).beginBizStep(with(equal(STEP.ROUTING)));
	            ignoring(memo).beginBizStep(with(equal(STEP.OBTAINING_HTTPCLIENT)));
	            ignoring(memo).beginBizStep(with(equal(STEP.TRANSFER_CONTENT)));
	            oneOf(memo).beginBizStep(with(equal(STEP.RECV_RESP)));
	            
	            allowing(memo).endBizStep(with(any(STEP.class)), with(any(long.class)));
	            
//	            oneOf(memo).incBizResult(with(equal(RESULT.SOURCE_CANCELED)), with(any(long.class)));
	            
	            allowing(memoBuilder).build(with(any(Target.class)), (RoutingInfo)with(anything()));
	            will(returnValue(memo));
	            
	            allowing(channelCtx).channel();
	            will(returnValue(null));
	            
	            allowing(channelCtx).close();
	        }   
	    });
		
		// execute
		final RelayFlow relay = new RelayFlow(router, memoBuilder, null)
			.attach(channelCtx, new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/test"));
		final EventReceiver receiver =  engine.create("testcase", relay.INIT, relay);
		
		final ServerTask task = EventUtils.buildInterfaceAdapter(ServerTask.class, receiver);
		task.onHttpContent(LastHttpContent.EMPTY_LAST_CONTENT);
		
		// verify
        jmock.assertIsSatisfied();
//		
//		assertEquals(lastStep, RelayMemo.STEP.RECV_RESP);
	}

	@Test
	public void testRelayRetry() throws Exception {
		final HttpRequest httpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
		final RelayFlow relay = new RelayFlow(router, memoBuilder, null)
			.attach(null, httpRequest);
		
		// enable return httpclient
		_isReturnHttpClient = true;
		final EventReceiver receiver =  engine.create("testcase", relay.INIT, relay);
		
		final AtomicReference<BizStep> current = new AtomicReference<BizStep>();
		relay.addFlowStateChangedListener(new FlowStateChangedListener<BizStep>() {

			@Override
			public void onStateChanged(BizStep prev,
					BizStep next, String causeEvent, Object[] causeArgs)
					throws Exception {
				current.set((BizStep)next);
			}});
		
		final ServerTask task = EventUtils.buildInterfaceAdapter(ServerTask.class, receiver);
		task.onHttpContent(LastHttpContent.EMPTY_LAST_CONTENT);
		
		// disable return httpclient
		_isReturnHttpClient = false;
		_reactor.onHttpClientLost(_guideCtx);
		assertEquals(lastResult, RelayMemo.RESULT.RELAY_RETRY);
		assertEquals(current.get(), relay.INIT);
	}
}
