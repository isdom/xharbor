/**
 * 
 */
package org.jocean.xharbor;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.concurrent.TimeUnit;

import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;

import org.jocean.event.api.EventReceiverSource;
import org.jocean.event.extend.Runners;
import org.jocean.event.extend.Services;
import org.jocean.httpclient.impl.HttpUtils;
import org.jocean.idiom.Function;
import org.jocean.idiom.Visitor;
import org.jocean.j2se.spring.BeanProxy;
import org.jocean.xharbor.relay.RelayAgentImpl;
import org.jocean.xharbor.relay.RelayContext;
import org.jocean.xharbor.relay.RelayContext.RESULT;
import org.jocean.xharbor.relay.RelayContext.RelayMemo;
import org.jocean.xharbor.relay.RelayContext.STEP;
import org.jocean.xharbor.route.CachedRouter;
import org.jocean.xharbor.route.Request2RoutingInfo;
import org.jocean.xharbor.route.RouteUtils;
import org.jocean.xharbor.route.RoutingInfo;
import org.jocean.xharbor.route.RoutingInfo2Targets;
import org.jocean.xharbor.route.RulesZKUpdater;
import org.jocean.xharbor.route.SelectTarget;
import org.jocean.xharbor.route.Target;
import org.jocean.xharbor.route.TargetSet;
import org.jocean.xharbor.route.Target2RelayCtx;
import org.jocean.xharbor.util.ServiceMemo;
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

    private static final String normalizeString(final String input) {
        return input.replaceAll(":", "-");
    }
    
    static class MemoBuilder implements Target2RelayCtx.RelayMemoBuilder {
        
        private final Timer _timer;

        MemoBuilder(final Timer timer) {
            this._timer = timer;
        }
        
        @Override
        public RelayMemo build(final Target target, final RoutingInfo info) {
            return new RelayContext.RelayMemo() {
                @Override
                public void beginBizStep(final STEP step) {
                }
                @Override
                public void endBizStep(final STEP step, final long ttl) {
                    if ( step.equals(STEP.RECV_RESP) ) {
                        //  < 500 ms
                        if ( ttl < 500 ) {
                            final int weight = target.addWeight(1);
                            if ( LOG.isDebugEnabled() ) {
                                LOG.debug("endBizStep for RECV_RESP with ttl < 500ms, so add weight with 1 to {}",
                                        weight);
                            }
                        }
                    }
                }
                @Override
                public void incBizResult(final RESULT result, final long ttl) {
                    if ( result.equals(RESULT.CONNECTDESTINATION_FAILURE)) {
                        markServiceDown4Result(60L, target, "CONNECTDESTINATION_FAILURE");
                    }
                    else if ( result.equals(RESULT.RELAY_FAILURE)) {
                        markAPIDown4Result(60L, target, info, "RELAY_FAILURE");
                    }
                    else if ( result.equals(RESULT.HTTP_CLIENT_ERROR)) {
                        markAPIDown4Result(60L, target, info, "HTTP_CLIENT_ERROR");
                    }
                    else if ( result.equals(RESULT.HTTP_SERVER_ERROR)) {
                        markAPIDown4Result(60L, target, info, "HTTP_SERVER_ERROR");
                    }
                }};
        }
        
        private void markAPIDown4Result(
                final long period, 
                final Target target, 
                final RoutingInfo info, 
                final String result) {
            target.markAPIDownStatus(true);
            LOG.warn("relay failed for {}, so mark service {}'s API {} down.",
                    result, target.serviceUri(), info);
            _timer.newTimeout(new TimerTask() {
                @Override
                public void run(final Timeout timeout) throws Exception {
                    // reset down flag
                    target.markAPIDownStatus(false);
                    LOG.info("reset service {}'s API {} down flag after {} second cause by {}.",
                            target.serviceUri(), info, period, result);
                }
            }, period, TimeUnit.SECONDS);
        }

        private void markServiceDown4Result(
                final long period, 
                final Target target,
                final String result) {
            target.markServiceDownStatus(true);
            LOG.warn("relay failed for {}, so mark service {} down.",
                    result, target.serviceUri());
            _timer.newTimeout(new TimerTask() {
                @Override
                public void run(final Timeout timeout) throws Exception {
                    // reset down flag
                    target.markServiceDownStatus(false);
                    LOG.info("reset service {} down flag after {} second cause by {}.",
                            target.serviceUri(), period, result);
                }
            }, period, TimeUnit.SECONDS);
        }

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
                new SelectTarget(ctx.getBean(ServiceMemo.class)),
                new Target2RelayCtx(new MemoBuilder(ctx.getBean(Timer.class)))
                ));
        
        ((BeanProxy<Visitor<RoutingInfo2Targets>>) checkNotNull(ctx.getBean("&updaterRules", BeanProxy.class)))
            .setImpl(new Visitor<RoutingInfo2Targets>() {
                @Override
                public void visit(final RoutingInfo2Targets rules) throws Exception {
                    cachedRouter.updateRouter(rules);
                }});
        
        final RulesZKUpdater updater = ctx.getBean(RulesZKUpdater.class);
        
        updater.start();
    }

}
