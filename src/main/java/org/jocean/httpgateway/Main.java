/**
 * 
 */
package org.jocean.httpgateway;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

import org.jocean.event.api.EventReceiverSource;
import org.jocean.event.extend.Runners;
import org.jocean.event.extend.Services;
import org.jocean.httpclient.HttpStack;
import org.jocean.httpclient.impl.HttpUtils;
import org.jocean.httpgateway.biz.DefaultDispatcher;
import org.jocean.httpgateway.biz.DefaultDispatcher.RuleSet;
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
        
        final HttpStack httpStack = new HttpStack(Pools.createCachedBytesPool(10240), source, new NettyClient(4), 100);
        final ProxyAgent agent = new ProxyAgentImpl(httpStack, source);
        
        final HttpGatewayServer server = ctx.getBean(HttpGatewayServer.class);
        
        server.setProxyAgent(agent);
        
        // construct dispatch
        final DefaultDispatcher dispatcher = new DefaultDispatcher();
        
        dispatcher.addRuleSet(new RuleSet(100) {{
            this.addTarget(new URI("http://121.41.100.24"), new Pattern[]{Pattern.compile("/ydd[/|\\w]*")});
        }} );
        
        dispatcher.addRuleSet(new RuleSet(0) {{
            this.addTarget(new URI("http://api.huaban.com"), new Pattern[]{Pattern.compile("/\\w*")});
        }} );
        
        server.setDispatcher(dispatcher);
    }

}
