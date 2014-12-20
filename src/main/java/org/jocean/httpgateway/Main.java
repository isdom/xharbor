/**
 * 
 */
package org.jocean.httpgateway;

import org.jocean.event.api.EventReceiverSource;
import org.jocean.event.extend.Runners;
import org.jocean.event.extend.Services;
import org.jocean.httpclient.HttpStack;
import org.jocean.httpclient.impl.HttpUtils;
import org.jocean.httpgateway.impl.ProxyAgentImpl;
import org.jocean.idiom.pool.Pools;
import org.jocean.netty.NettyClient;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * @author isdom
 *
 */
public class Main {

    /**
     * @param args
     */
    public static void main(String[] args) {
        
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
        
        final HttpStack httpStack = new HttpStack(Pools.createCachedBytesPool(10240), source, new NettyClient(4), 100);
        final ProxyAgent agent = new ProxyAgentImpl(httpStack, source);
        
        final HttpGatewayServer server = ctx.getBean(HttpGatewayServer.class);
        
        server.setProxyAgent(agent);
    }

}
