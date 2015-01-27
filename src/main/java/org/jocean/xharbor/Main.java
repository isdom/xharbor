/**
 * 
 */
package org.jocean.xharbor;

import static com.google.common.base.Preconditions.checkNotNull;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.Timer;

import org.jocean.event.api.EventReceiverSource;
import org.jocean.event.extend.Runners;
import org.jocean.event.extend.Services;
import org.jocean.idiom.Function;
import org.jocean.idiom.Visitor;
import org.jocean.j2se.spring.BeanProxy;
import org.jocean.xharbor.route.CachedRouter;
import org.jocean.xharbor.route.Request2RoutingInfo;
import org.jocean.xharbor.route.RouteUtils;
import org.jocean.xharbor.route.RoutingInfo2Dispatcher;
import org.jocean.xharbor.spi.Dispatcher;
import org.jocean.xharbor.spi.RelayMemo;
import org.jocean.xharbor.spi.Router;
import org.jocean.xharbor.spi.RoutingInfo;
import org.jocean.xharbor.util.RelayMemoBuilderForDispatchFeedback;
import org.jocean.xharbor.util.RelayMemoBuilderForStats;
import org.jocean.xharbor.util.RulesZKUpdater;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * @author isdom
 *
 */
public class Main {
    private static final String normalizeString(final String input) {
        return input.replaceAll(":", "-");
    }
    
    /**
     * @param args
     * @throws Exception 
     */
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        
        @SuppressWarnings("resource")
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
        
        ((BeanProxy<EventReceiverSource>) checkNotNull(ctx.getBean("&source", BeanProxy.class)))
            .setImpl(source);
        
        final CachedRouter<RoutingInfo, Dispatcher> cachedRouter = 
                RouteUtils.buildCachedRouter(
                        "org.jocean:type=router", 
                        source, 
                        new Function<RoutingInfo,String>() {
                            @Override
                            public String apply(final RoutingInfo info) {
                                return "path=" + normalizeString(info.getPath()) + ",method=" + info.getMethod()+",name=routes";
                            }});
        
        ((BeanProxy<Router<HttpRequest, Dispatcher>>) checkNotNull(ctx.getBean("&router", BeanProxy.class)))
            .setImpl(
                RouteUtils.compositeRouter(
                    Dispatcher.class,
                    new Request2RoutingInfo(), 
                    cachedRouter
                    ));
        
        ((BeanProxy<RelayMemo.Builder>) checkNotNull(ctx.getBean("&memoBuilder", BeanProxy.class)))
            .setImpl(
                RelayMemo.Utils.compositeBuilder(
                    new RelayMemoBuilderForStats(),
                    new RelayMemoBuilderForDispatchFeedback(ctx.getBean(Timer.class))
                    ));
        
        ((BeanProxy<Visitor<RoutingInfo2Dispatcher>>) checkNotNull(ctx.getBean("&updaterRules", BeanProxy.class)))
            .setImpl(new Visitor<RoutingInfo2Dispatcher>() {
                @Override
                public void visit(final RoutingInfo2Dispatcher rules) throws Exception {
                    cachedRouter.updateRouter(rules);
                }});
        
        final RulesZKUpdater updater = ctx.getBean(RulesZKUpdater.class);
        
        updater.start();
    }

}
