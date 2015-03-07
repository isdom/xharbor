package org.jocean.xharbor.relay;

import static org.junit.Assert.assertEquals;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import org.jmock.Expectations;
import org.jmock.Mockery;
import org.jmock.States;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventEngine;
import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.EventUtils;
import org.jocean.event.api.FlowStateChangedListener;
import org.jocean.event.core.FlowContainer;
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
		
		final HttpClient	httpclient = jmock.mock(HttpClient.class);
		final Guide 		guide2nd = jmock.mock(Guide.class);
		final GuideBuilder 	guideBuilder = jmock.mock(GuideBuilder.class);
		final Target 		target = jmock.mock(Target.class);
		final Dispatcher 	dispatcher = jmock.mock(Dispatcher.class);
		final Router<HttpRequest, Dispatcher> router = 
				(Router<HttpRequest, Dispatcher>)jmock.mock(Router.class);
		final RelayMemo 	memo = jmock.mock(RelayMemo.class);
		final RelayMemo.Builder	memoBuilder = jmock.mock(RelayMemo.Builder.class);
		
		final ChannelHandlerContext channelCtx = jmock.mock(ChannelHandlerContext.class);
		
		final States guidefsm = jmock.states("guide").startsAs("init");
		
		final AtomicReference<Object> ctxRef = new AtomicReference<Object>();
		final AtomicReference<GuideReactor<Object>> reactorRef = new AtomicReference<GuideReactor<Object>>();
		
		final Guide guide = new Guide() {
			@Override
			public void detach() throws Exception {
			}
			@Override
			public <CTX> void obtainHttpClient(
					final CTX ctx,
					final GuideReactor<CTX> reactor, 
					final Requirement requirement) {
				try {
					ctxRef.set(ctx);
					reactorRef.set((GuideReactor<Object>)reactor);
					reactor.onHttpClientObtained(ctx, httpclient);
				} catch (Exception e) {
				}
			}
			Object _currentGuideCtx;
			Guide.GuideReactor<Object> _currentReactor;
		};
			
		jmock.checking(new Expectations() {   
	        {   
	        	allowing(httpclient).sendHttpRequest(
	        			with(anything()), 
	        			with(any(HttpRequest.class)), 
	        			with(any(HttpReactor.class)));
	        	
	        	allowing(httpclient).sendHttpContent(
	        			with(any(HttpContent.class)));
	        	
	        	allowing(guide2nd).obtainHttpClient(
	        			with(anything()), 
	        			with(any(GuideReactor.class)), 
	        			with(any(Requirement.class)));
	        	
	        	allowing(guideBuilder).createHttpClientGuide();
	        	when(guidefsm.is("init"));
	            will(returnValue(guide));
	            then(guidefsm.is("2nd"));
	            
	        	allowing(guideBuilder).createHttpClientGuide();
	        	when(guidefsm.is("2nd"));
	            will(returnValue(guide2nd));
	            
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
	            ignoring(memo).beginBizStep(with(equal(STEP.RECV_RESP)));
	            
	            allowing(memo).endBizStep(with(any(STEP.class)), with(any(long.class)));
	            
	            ignoring(memo).incBizResult(with(equal(RESULT.RELAY_FAILURE)), with(any(long.class)));
	            oneOf(memo).incBizResult(with(equal(RESULT.RELAY_RETRY)), with(any(long.class)));
	            
	            allowing(memoBuilder).build(with(any(Target.class)), (RoutingInfo)with(anything()));
	            will(returnValue(memo));
	            
	            allowing(channelCtx).channel();
	            will(returnValue(null));
	            
	            allowing(channelCtx).close();
	        }   
	    });
		
		final RelayFlow relay = new RelayFlow(router, memoBuilder, null)
			.attach(null, new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/test"));
		
		final EventReceiver receiver =  engine.create("testcase", relay.INIT, relay);
		
		final AtomicReference<BizStep> current = new AtomicReference<BizStep>();
		relay.addFlowStateChangedListener(new FlowStateChangedListener<BizStep>() {

			@Override
			public void onStateChanged(
					BizStep prev,
					BizStep next, String causeEvent, Object[] causeArgs)
					throws Exception {
				current.set(next);
			}});
		
		final ServerTask task = EventUtils.buildInterfaceAdapter(ServerTask.class, receiver);
		task.onHttpContent(LastHttpContent.EMPTY_LAST_CONTENT);

		// for httpclient lost
		reactorRef.get().onHttpClientLost(ctxRef.get());
		
		assertEquals(relay.INIT, current.get());
	}
}
