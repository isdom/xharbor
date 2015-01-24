/**
 * 
 */
package org.jocean.xharbor;

import static com.google.common.base.Preconditions.checkNotNull;

import org.jocean.event.api.EventReceiverSource;
import org.jocean.event.extend.Runners;
import org.jocean.event.extend.Services;
import org.jocean.httpclient.impl.HttpUtils;
import org.jocean.idiom.Function;
import org.jocean.idiom.Visitor;
import org.jocean.j2se.spring.BeanProxy;
import org.jocean.xharbor.relay.RelayAgentImpl;
import org.jocean.xharbor.relay.RelayContext;
import org.jocean.xharbor.route.CachedRouter;
import org.jocean.xharbor.route.Request2RoutingInfo;
import org.jocean.xharbor.route.RouteUtils;
import org.jocean.xharbor.route.RoutingInfo;
import org.jocean.xharbor.route.RoutingInfo2URIs;
import org.jocean.xharbor.route.RulesZKUpdater;
import org.jocean.xharbor.route.SelectURI;
import org.jocean.xharbor.route.TargetSet;
import org.jocean.xharbor.route.URI2RelayCtxOfRoutingInfo;
import org.jocean.xharbor.util.URISMemo;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * @author isdom
 *
 */
public class Main {
//    private static final Logger LOG = LoggerFactory
//            .getLogger(Main.class);

    private static final String normalizeString(final String input) {
        return input.replaceAll(":", "-");
    }
    
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
        
        ((BeanProxy<EventReceiverSource>) checkNotNull(ctx.getBean("&source", BeanProxy.class))).setImpl(source);
        
        final RelayAgentImpl relayAgent = ctx.getBean(RelayAgentImpl.class);
        
        final CachedRouter<RoutingInfo, TargetSet> cachedRouter = 
                RouteUtils.buildCachedURIsRouter(
                        "org.jocean:type=router", 
                        source, 
                        new Function<RoutingInfo,String>() {
                            @Override
                            public String apply(final RoutingInfo info) {
                                return "path=" + normalizeString(info.getPath()) + ",method=" + info.getMethod()+",name=routes";
                            }});
        
        relayAgent.setRouter(RouteUtils.buildCompositeRouter(
                new Request2RoutingInfo(), RelayContext.class,
                cachedRouter,
                new SelectURI(ctx.getBean(URISMemo.class)),
                new URI2RelayCtxOfRoutingInfo()
                ));
        
        ((BeanProxy<Visitor<RoutingInfo2URIs>>) checkNotNull(ctx.getBean("&updaterRules", BeanProxy.class)))
            .setImpl(new Visitor<RoutingInfo2URIs>() {
                @Override
                public void visit(final RoutingInfo2URIs rules) throws Exception {
                    cachedRouter.updateRouter(rules);
                }});
        
        final RulesZKUpdater updater = ctx.getBean(RulesZKUpdater.class);
        
        updater.start();
                
//        final MBeanRegisterSupport register =
//                new MBeanRegisterSupport("org.jocean:name=htmladapter", null);
//        HtmlAdaptorServer adapter = new HtmlAdaptorServer(); 
//        register.registerMBean("port=8082", adapter);
//        adapter.start();
    }

}
