/**
 * 
 */
package org.jocean.httpgateway.impl;

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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

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
import org.jocean.idiom.ValidationId;
import org.jocean.idiom.block.Blob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 * 
 */
public class RelayFlow extends AbstractFlow<RelayFlow> {

    private static final Logger LOG = LoggerFactory
            .getLogger(RelayFlow.class);

    public RelayFlow(final Guide guide, final RelayContext relayCtx, final ChannelHandlerContext channelCtx) {
        this._guide = guide;
        this._relayCtx = relayCtx;
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

            _relayCtx.memo().incObtainingHttpClient();
            _httpRequest = httpRequest;
            updateTransferHttpRequestState(_httpRequest);
            
            startObtainHttpClient();
            return OBTAINING;
        }
    }
    .handler(handlersOf(new ONDETACH(new Runnable() {
        @Override
        public void run() {
            _relayCtx.memo().incSourceCanceled();
        }})))
    .freeze();

    private final BizStep OBTAINING = new BizStep("relay.OBTAINING") {
        @OnEvent(event = "sendHttpContent")
        private BizStep sendHttpContent(final HttpContent httpContent) {

            _contents.add(httpContent);
            updateTransferHttpRequestState(httpContent);
            
            return currentEventHandler();
        }
        
        @OnEvent(event = "onHttpClientObtained")
        private BizStep onHttpObtained(final int guideId,
                final HttpClient httpclient) {
            if (!isValidGuideId(guideId)) {
                return currentEventHandler();
            }

            _relayCtx.memo().decObtainingHttpClient();
            
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
                _relayCtx.memo().incTransferContent();
                return TRANSFERCONTENT;
            }
            else {
                tryStartForceFinishedTimer();
                _relayCtx.memo().incRecvResp();
                return RECVRESP;
            }
        }
    }
    .handler(handlersOf(new ONHTTPLOST(new Runnable() {
        @Override
        public void run() {
            _relayCtx.memo().decObtainingHttpClient();
            _relayCtx.memo().incConnectDestinationFailure();
        }})))
    .handler(handlersOf(new ONDETACH(new Runnable() {
        @Override
        public void run() {
            _relayCtx.memo().decObtainingHttpClient();
            _relayCtx.memo().incSourceCanceled();
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
                _relayCtx.memo().decTransferContent();
                _relayCtx.memo().incRecvResp();
                return RECVRESP;
            }
        }
    }
    .handler(handlersOf(new ONHTTPLOST(new Runnable() {
        @Override
        public void run() {
            _relayCtx.memo().decTransferContent();
            _relayCtx.memo().incRelayFailure();
        }})))
    .handler(handlersOf(new ONDETACH(new Runnable() {
        @Override
        public void run() {
            _relayCtx.memo().decTransferContent();
            _relayCtx.memo().incSourceCanceled();
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
            _channelCtx.write(response);
            return RECVCONTENT;
        }
    }
    .handler(handlersOf(new ONHTTPLOST(new Runnable() {
        @Override
        public void run() {
            _relayCtx.memo().decRecvResp();
            _relayCtx.memo().incRelayFailure();
        }})))
    .handler(handlersOf(new ONDETACH(new Runnable() {
        @Override
        public void run() {
            _relayCtx.memo().decRecvResp();
            _relayCtx.memo().incSourceCanceled();
        }})))
    .freeze();

    private final BizStep RECVCONTENT = new BizStep("relay.RECVCONTENT") {
        @OnEvent(event = "onHttpContentReceived")
        private BizStep contentReceived(final int httpClientId,
                final Blob contentBlob) throws Exception {
            if (!isValidHttpClientId(httpClientId)) {
                return currentEventHandler();
            }
            
            _channelCtx.write(new DefaultHttpContent(blob2ByteBuf(contentBlob)));
            return RECVCONTENT;
        }

        @OnEvent(event = "onLastHttpContentReceived")
        private BizStep lastContentReceived(final int httpClientId,
                final Blob contentBlob) throws Exception {
            if (!isValidHttpClientId(httpClientId)) {
                return currentEventHandler();
            }

            //  release relay's http client
            safeDetachHttp();
            
            final ChannelFuture future = _channelCtx.writeAndFlush(new DefaultLastHttpContent(blob2ByteBuf(contentBlob)));
            if ( !HttpHeaders.isKeepAlive( _httpRequest ) ) {
                future.addListener(ChannelFutureListener.CLOSE);
            }
            _relayCtx.memo().decRecvResp();
            _relayCtx.memo().incRelaySuccess();
            return null;
        }

        /**
         * @param contentBlob
         * @return
         * @throws IOException
         */
        private ByteBuf blob2ByteBuf(final Blob contentBlob) throws IOException {
            byte[] bytes = new byte[0];
            if ( null != contentBlob ) {
                final InputStream is = contentBlob.genInputStream();
                bytes = new byte[is.available()];
                is.read(bytes);
                is.close();
            }
            return Unpooled.wrappedBuffer(bytes);
        }
    }
    .handler(handlersOf(new ONHTTPLOST(new Runnable() {
        @Override
        public void run() {
            _relayCtx.memo().decRecvResp();
            _relayCtx.memo().incRelayFailure();
        }})))
    .handler(handlersOf(new ONDETACH(new Runnable() {
        @Override
        public void run() {
            _relayCtx.memo().decRecvResp();
            _relayCtx.memo().incSourceCanceled();
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
            flow._contents.clear();
        }
    };
    
    private final Guide _guide;
    private final RelayContext _relayCtx;
    private final ChannelHandlerContext _channelCtx;
    
    private HttpClient _httpClient;
    private HttpRequest _httpRequest;
    private final List<HttpContent> _contents = new ArrayList<HttpContent>();
    private boolean _transferHttpRequestComplete = false;
    
    private final ValidationId _guideId = new ValidationId();
    private final ValidationId _httpClientId = new ValidationId();
    private Detachable _forceFinishedTimer;
    private long _timeoutFromActived = -1;
}
