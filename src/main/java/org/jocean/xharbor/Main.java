/**
 * 
 */
package org.jocean.xharbor;

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
import org.jocean.xharbor.relay.RelayAgent;
import org.jocean.xharbor.relay.impl.DispatcherImpl;
import org.jocean.xharbor.relay.impl.MemoFactoryImpl;
import org.jocean.xharbor.relay.impl.RelayAgentImpl;
import org.jocean.xharbor.route.impl.RouteUtils;
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
        
        final HttpGatewayServer server = ctx.getBean(HttpGatewayServer.class);
        
        final RelayAgent agent = new RelayAgentImpl(httpStack, source);
        server.setRelayAgent(agent);
        
        final CuratorFramework client = 
                CuratorFrameworkFactory.newClient("121.41.45.51:2181", 
                        new ExponentialBackoffRetry(1000, 3));
        client.start();
        
        final DispatcherImpl dispatcher = new DispatcherImpl(source, new MemoFactoryImpl());
         
        server.setHttpDispatcher(dispatcher);
        
        dispatcher.updateRouteProvider(RouteUtils.buildRouteProviderFromZK(client, "/demo"));
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
                    dispatcher.updateRouteProvider(RouteUtils.buildRouteProviderFromZK(client, "/demo"));
                    break;
                default:
                    LOG.debug("childEvent: {} event received.", event);
                }
                
            }});
        cache.start();
    }

}
