/**
 * 
 */
package org.jocean.xharbor;

import io.netty.handler.codec.http.HttpRequest;

import java.net.URI;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.TreeCache;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.apache.curator.framework.recipes.cache.TreeCacheListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.jocean.event.api.EventReceiverSource;
import org.jocean.event.extend.Runners;
import org.jocean.event.extend.Services;
import org.jocean.httpclient.HttpStack;
import org.jocean.httpclient.impl.HttpUtils;
import org.jocean.idiom.pool.Pools;
import org.jocean.netty.NettyClient;
import org.jocean.xharbor.relay.RelayAgentImpl;
import org.jocean.xharbor.relay.RelayContext;
import org.jocean.xharbor.route.Request2PathRouter;
import org.jocean.xharbor.route.RouteUtils;
import org.jocean.xharbor.route.SelectURIRouter;
import org.jocean.xharbor.route.URI2RelayCtxRouter;
import org.jocean.xharbor.spi.RelayAgent;
import org.jocean.xharbor.spi.Router;
import org.jocean.xharbor.spi.RouterUpdatable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * @author isdom
 *
 */
public class Main {
    private static final Logger LOG = LoggerFactory
            .getLogger(Main.class);

    /**
     * @param args
     * @throws Exception 
     */
    public static void main(String[] args) throws Exception {
        
        HttpUtils.enableHttpTransportLog(true);
        
        final AbstractApplicationContext ctx =
                new ClassPathXmlApplicationContext(
                        new String[]{"xharbor.xml"});
        
        final EventReceiverSource source = 
                Runners.build(new Runners.Config()
                    .objectNamePrefix("org.jocean:type=xharbor")
                    .name("xharbor")
                    .timerService(Services.lookupOrCreateTimerService("xharbor"))
                    .executorSource(Services.lookupOrCreateFlowBasedExecutorSource("xharbor"))
                    );
        
        final HttpStack httpStack = new HttpStack(
                Pools.createCachedBytesPool(10240), 
                source, 
                new NettyClient(4), 
                100);
        
        final HttpGatewayServer<RelayContext> server = ctx.getBean(HttpGatewayServer.class);
        
        final RelayAgent<RelayContext> agent = new RelayAgentImpl(httpStack, source);
        server.setRelayAgent(agent);
        
        final CuratorFramework client = 
                CuratorFrameworkFactory.newClient("121.41.45.51:2181", 
                        new ExponentialBackoffRetry(1000, 3));
        client.start();
        
//        final DispatcherImpl dispatcher = new DispatcherImpl(source, new MemoFactoryImpl());
         
        final Router<String, URI[]> cachedRouter = RouteUtils.buildCachedPathRouter("org.jocean:type=router", source);
        ((RouterUpdatable<String, URI[]>)cachedRouter).updateRouter(RouteUtils.buildPathRouterFromZK(client, "/demo"));
        
        server.setRouter(RouteUtils.buildCompositeRouter(
                new Request2PathRouter(), RelayContext.class,
                cachedRouter,
                new SelectURIRouter(),
                new URI2RelayCtxRouter()
                ));
        
        final TreeCache cache = TreeCache.newBuilder(client, "/demo").setCacheData(false).build();
        cache.getListenable().addListener(new TreeCacheListener() {

            @Override
            public void childEvent(final CuratorFramework client, final TreeCacheEvent event)
                    throws Exception {
                switch (event.getType()) {
                case NODE_ADDED:
                case NODE_UPDATED:
                case NODE_REMOVED:
                    LOG.debug("childEvent: {} event received, rebuild dispatcher", event);
                    ((RouterUpdatable<String, URI[]>)cachedRouter).updateRouter(RouteUtils.buildPathRouterFromZK(client, "/demo"));
                    break;
                default:
                    LOG.debug("childEvent: {} event received.", event);
                }
                
            }});
        cache.start();
    }

}
