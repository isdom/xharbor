/**
 * 
 */
package org.jocean.httpgateway;

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
import org.jocean.httpgateway.biz.RelayAgent;
import org.jocean.httpgateway.impl.DefaultDispatcher;
import org.jocean.httpgateway.impl.RelayAgentImpl;
import org.jocean.httpgateway.impl.MemoFactoryImpl;
import org.jocean.httpgateway.route.RouteUtils;
import org.jocean.idiom.pool.Pools;
import org.jocean.netty.NettyClient;
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
                        new String[]{"gateway.xml"});
        
        final EventReceiverSource source = 
                Runners.build(new Runners.Config()
                    .objectNamePrefix("org.jocean:type=gateway")
                    .name("gateway")
                    .timerService(Services.lookupOrCreateTimerService("gateway"))
                    .executorSource(Services.lookupOrCreateFlowBasedExecutorSource("gateway"))
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
        
        final DefaultDispatcher dispatcher = new DefaultDispatcher(new MemoFactoryImpl());
         
        server.setHttpDispatcher(dispatcher);
        
        dispatcher.setRoutingRules(RouteUtils.buildRoutingRulesFromZK(client, "/demo"));
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
                    dispatcher.setRoutingRules(RouteUtils.buildRoutingRulesFromZK(client, "/demo"));
                    break;
                default:
                    LOG.debug("childEvent: {} event received.", event);
                }
                
            }});
        cache.start();
    }

}
