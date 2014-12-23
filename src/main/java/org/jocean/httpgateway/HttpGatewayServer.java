/**
 * 
 */
package org.jocean.httpgateway;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.traffic.TrafficCounterExt;
import io.netty.util.AttributeKey;

import java.io.IOException;
import java.net.URI;

import org.jocean.ext.netty.initializer.BaseInitializer;
import org.jocean.httpgateway.ProxyAgent.ProxyTask;
import org.jocean.httpgateway.biz.HttpRequestDispatcher;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class HttpGatewayServer {
    
    private static final Logger LOG = LoggerFactory
            .getLogger(HttpGatewayServer.class);

    public static final AttributeKey<ProxyTask> PROXY = AttributeKey.valueOf("Proxy");
    
    private static final int MAX_RETRY = 20;
    private static final long RETRY_TIMEOUT = 30 * 1000; // 30s
    
    private Channel _acceptorChannel;
    
    private int _acceptPort = 65000;
    private String _acceptIp = "0.0.0.0";
    private final ServerBootstrap _bootstrap = new ServerBootstrap();
    private final EventLoopGroup _acceptorGroup = new NioEventLoopGroup();
    private final EventLoopGroup _clientGroup = new NioEventLoopGroup();
    
    private TrafficCounterExt _trafficCounterExt;
    private boolean _logByteStream = false;
    private int _idleTimeSeconds = 180; //seconds  为了避免建立了过多的闲置连接 闲置180秒的连接主动关闭
//    private int _chunkDataSzie = 1048576; //1024 * 1024
    
    private ProxyAgent  _proxyAgent;
//    private String _destUri = "http://127.0.0.1:8000";
    private HttpRequestDispatcher _dispatcher;
    
    /**
     * 负责处理来自终端的request到AccessCenterBiz
     * @author Bluces.Wang@sky-mobi.com
     *
     */
    @ChannelHandler.Sharable
    private class ProxyHandler extends ChannelInboundHandlerAdapter{
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            LOG.info("exceptionCaught {}, detail:{}", 
                    ctx.channel(), ExceptionUtils.exception2detail(cause));
            ctx.close();
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            ctx.flush();
        }
        
        private ProxyTask getProxyTaskOf(final ChannelHandlerContext ctx) {
            return ctx.attr(PROXY).get();
        }
        
        private void setProxyTaskOf(final ChannelHandlerContext ctx, final ProxyTask task) {
            ctx.attr(PROXY).set(task);
        }
        
        /**
         * @param ctx
         * @throws Exception
         */
        private void detachCurrentTaskOf(ChannelHandlerContext ctx)
                throws Exception {
            final ProxyTask task = getProxyTaskOf(ctx);
            if ( null != task ) {
                task.detach();
            }
        }

        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
            
            if (msg instanceof HttpRequest) {
                final HttpRequest request = (HttpRequest)msg;
                
                if ( LOG.isDebugEnabled()) {
                    LOG.debug("messageReceived:{} default http request\n[{}]",ctx.channel(),request);
                }
                
                final URI dest = _dispatcher.dispatch(request);
                
                if ( null == dest ) {
                    LOG.warn("can't found matched dest uri for request {}, just ignore", request);
                    return;
                }
                
                if ( LOG.isDebugEnabled() ) {
                    LOG.debug("dispatch to ({}) for request({})", dest, request);
                }
                
                detachCurrentTaskOf(ctx);
                
                final ProxyTask newTask = _proxyAgent.createProxyTask(dest, ctx);
                newTask.sendHttpRequest(request);
                setProxyTaskOf(ctx, newTask);
            }
            else if (msg instanceof HttpContent) {
                final ProxyTask task = getProxyTaskOf(ctx);
                if ( null != task ) {
                    task.sendHttpContent((HttpContent)msg);
                }
                else {
                    LOG.warn("NO PROXY TASK, messageReceived:{} unhandled msg [{}]",ctx.channel(),msg);
                }
            }
            else {
                LOG.warn("messageReceived:{} unhandled msg [{}]",ctx.channel(),msg);
                return;
            }
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            detachCurrentTaskOf(ctx);
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
        }
    }
    
    public void start() throws IOException, InterruptedException {
        this._bootstrap.group(this._acceptorGroup,this._clientGroup)
                .channel(NioServerSocketChannel.class)
                .localAddress(this._acceptIp,this._acceptPort)
                /*
                 * SO_BACKLOG
                 * Creates a server socket and binds it to the specified local port number, with the specified backlog.
                 * The maximum queue length for incoming connection indications (a request to connect) is set to the backlog parameter. 
                 * If a connection indication arrives when the queue is full, the connection is refused. 
                 */
                .option(ChannelOption.SO_BACKLOG, 10240)
                /*
                 * SO_REUSEADDR
                 * Allows socket to bind to an address and port already in use. The SO_EXCLUSIVEADDRUSE option can prevent this. 
                 * Also, if two sockets are bound to the same port the behavior is undefined as to which port will receive packets.
                 */
                .option(ChannelOption.SO_REUSEADDR, true)
                /*
                 * SO_RCVBUF
                 * The total per-socket buffer space reserved for receives. 
                 * This is unrelated to SO_MAX_MSG_SIZE and does not necessarily correspond to the size of the TCP receive window. 
                 */
                //.option(ChannelOption.SO_RCVBUF, 8 * 1024)
                /*
                 * SO_SNDBUF
                 * The total per-socket buffer space reserved for sends. 
                 * This is unrelated to SO_MAX_MSG_SIZE and does not necessarily correspond to the size of a TCP send window.
                 */
                //.option(ChannelOption.SO_SNDBUF, 8 * 1024)
                /*
                 * SO_KEEPALIVE
                 * Enables keep-alive for a socket connection. 
                 * Valid only for protocols that support the notion of keep-alive (connection-oriented protocols). 
                 * For TCP, the default keep-alive timeout is 2 hours and the keep-alive interval is 1 second. 
                 * The default number of keep-alive probes varies based on the version of Windows. 
                 */
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                /*
                 * TCP_NODELAY
                 * Enables or disables the Nagle algorithm for TCP sockets. This option is disabled (set to FALSE) by default.
                 */
                .childOption(ChannelOption.TCP_NODELAY, true)
                /*
                 * SO_LINGER
                 * Indicates the state of the linger structure associated with a socket. 
                 * If the l_onoff member of the linger structure is nonzero, 
                 * a socket remains open for a specified amount of time 
                 * after a closesocket function call to enable queued data to be sent. 
                 * The amount of time, in seconds, to remain open 
                 * is specified in the l_linger member of the linger structure. 
                 * This option is only valid for reliable, connection-oriented protocols.
                 */
                .childOption(ChannelOption.SO_LINGER, -1);
        
        final BaseInitializer baseInitializer = new BaseInitializer(){
            @Override
            protected void addCodecHandler(final ChannelPipeline pipeline) throws Exception {
                //IN decoder
                pipeline.addLast("decoder",new HttpRequestDecoder());
                //IN aggregator
//                pipeline.addLast("aggregator", new HttpObjectAggregator(_chunkDataSzie));
                //OUT 统计数据流大小 这个handler需要放在HttpResponseToByteEncoder前面处理
//                pipeline.addLast("statistics",new StatisticsResponseHandler()); 
                //OUT encoder
                pipeline.addLast("encoder-object",new HttpResponseEncoder());
                
                //IN/OUT 支持压缩
                pipeline.addLast("deflater", new HttpContentCompressor());
            }
            
            @Override
            protected void addBusinessHandler(final ChannelPipeline pipeline) throws Exception {
                pipeline.addLast("biz-handler", new ProxyHandler());
                
            }
        };
        
        baseInitializer.setLogByteStream(this._logByteStream);
        baseInitializer.setLogMessage(false);//因为响应可能不是message，logMessage无法处理
        
        if ( null != this._trafficCounterExt) {
            baseInitializer.setTrafficCounter(this._trafficCounterExt);
        }
        baseInitializer.setIdleTimeSeconds(this._idleTimeSeconds);
        
        _bootstrap.childHandler(baseInitializer);

        int retryCount = 0;
        boolean binded = false;

        do {
            try {
                this._acceptorChannel = this._bootstrap.bind().channel();
                binded = true;
            } catch (final ChannelException e) {
                LOG.warn("start failed : {}, and retry...", e);

                //  对绑定异常再次进行尝试
                retryCount++;
                if (retryCount >= MAX_RETRY) {
                    //  超过最大尝试次数
                    throw e;
                }
                try {
                    Thread.sleep(RETRY_TIMEOUT);
                } catch (InterruptedException ignored) {
                }
            }
        } while (!binded);
        
    }

    public void stop() {
        if (null != this._acceptorChannel) {
            this._acceptorChannel.disconnect();
            this._acceptorChannel = null;
        }
        this._acceptorGroup.shutdownGracefully();
        this._clientGroup.shutdownGracefully();
    }
    
    public void setAcceptPort(int acceptPort) {
        this._acceptPort = acceptPort;
    }
 

    public void setAcceptIp(String acceptIp) {
        this._acceptIp = acceptIp;
    }

//    public void setSessionFactory(SessionFactory sessionFactory) {
//        this.sessionFactory = sessionFactory;
//    }
//
//    public void setCenterFSM(FiniteStateMachine centerFSM) {
//        this.centerFSM = centerFSM;
//    }
//
//    public int getChannelCount() {
//        return channelCount.intValue();
//    }

    public void setLogByteStream(boolean logByteStream) {
        this._logByteStream = logByteStream;
    }

    public void setIdleTimeSeconds(int idleTimeSeconds) {
        this._idleTimeSeconds = idleTimeSeconds;
    }
 
    /*public List<String> getSessionMap(int fakeValue){
        List<String> ret = new ArrayList<String>();
        for(Session value:SESSIONMAP.values()){
            ret.add(value.toString());
        }
        return ret;
    }*/

//    public void setPacketsFrequency(int packetsFrequency) {
//        this.packetsFrequency = packetsFrequency;
//    }

    public void setTrafficCounterExt(TrafficCounterExt trafficCounterExt) {
        this._trafficCounterExt = trafficCounterExt;
    }

    public void setProxyAgent(final ProxyAgent agent) {
        this._proxyAgent = agent;
    }
    
    public void setDispatcher(final HttpRequestDispatcher dispatcher) {
        this._dispatcher = dispatcher;
    }
    
    public void setDestUri(final String uri) {
//        this._destUri = uri;
    }
    
    public String getDestUri() {
//        return this._destUri;
        return null;
    }
    
//    public void setGroupStatisticsFSM(FiniteStateMachine groupStatisticsFSM) {
//        this.groupStatisticsFSM = groupStatisticsFSM;
//    }
//
//    public List<String> getSupportCompressUriList() {
//        return supportCompressUriList;
//    }
//
//    public void setSupportCompressUriList(List<String> supportCompressUriList) {
//        this.supportCompressUriList = supportCompressUriList;
//    }
}
