/**
 * 
 */
package org.jocean.xharbor;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jocean.idiom.Function;
import org.jocean.idiom.Pair;
import org.jocean.idiom.Visitor;
import org.jocean.idiom.Visitor2;
import org.jocean.j2se.MBeanRegisterSupport;
import org.jocean.j2se.spring.BeanProxy;
import org.jocean.xharbor.api.Dispatcher;
import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.route.CachedRouter;
import org.jocean.xharbor.route.RoutingInfo2Dispatcher;
import org.jocean.xharbor.spi.HttpRequestTransformer;
import org.jocean.xharbor.transform.CompositeAUPBuilder;
import org.jocean.xharbor.util.InfoListMaker;
import org.jocean.xharbor.util.RulesMXBean;
import org.jocean.xharbor.util.ZKUpdater;
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
    
    public static class StatsImpl implements RulesMXBean {

        @Override
        public String[] getRoutingRules() {
            return new ArrayList<String>() {
                private static final long serialVersionUID = 1L;
            {
                for (Map.Entry<String, InfoListMaker> entry : _register.entrySet()) {
                    final List<String> infos = new ArrayList<String>();
                    entry.getValue().addInfoList(infos);
                    final StringBuilder sb = new StringBuilder();
                    sb.append(entry.getKey());
                    sb.append('\t');
                    sb.append("-->");
                    for ( String s : infos) {
                        sb.append('\n');
                        sb.append('\t');
                        sb.append(s);
                    }
                    this.add(sb.toString());
                }
            }}.toArray(new String[0]);
        }
        
        public void register(final String name, final InfoListMaker maker) {
            this._register.put(name, maker);
        }
        
        private final Map<String, InfoListMaker> _register = 
                new ConcurrentHashMap<String, InfoListMaker>();
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
        
        ((BeanProxy<Function<Pair<RoutingInfo,Dispatcher>,String>>)
            checkNotNull(ctx.getBean("&genObjname", BeanProxy.class)))
            .setImpl(new Function<Pair<RoutingInfo,Dispatcher>,String>() {
                @Override
                public String apply(final Pair<RoutingInfo,Dispatcher> input) {
                    final Dispatcher dispatcher = input.getSecond();
                    if (dispatcher.IsValid()) {
                        final RoutingInfo info = input.getFirst();
                        return "path=" + normalizeString(info.getPath())
                                +",method=" + info.getMethod()
                                +",name=routes";
                    }
                    else {
                        return null;
                    }
                }});
        
        final StatsImpl statsImpl = new StatsImpl();
        
        final MBeanRegisterSupport mbeanSupport = 
                new MBeanRegisterSupport("org.jocean:type=router", null);
        
        mbeanSupport.registerMBean("name=stats", statsImpl);
        
        ((BeanProxy<Visitor2<String,InfoListMaker>>)
                checkNotNull(ctx.getBean("&statsRegister", BeanProxy.class)))
                .setImpl(new Visitor2<String,InfoListMaker>() {
                    @Override
                    public void visit(final String name, final InfoListMaker maker) {
                        statsImpl.register(name, maker);
                    }});
        
        final CachedRouter<RoutingInfo, Dispatcher> cachedRouter = 
                (CachedRouter<RoutingInfo, Dispatcher>)ctx.getBean("cachedRouter");
        
        ((BeanProxy<Visitor<RoutingInfo2Dispatcher>>) checkNotNull(ctx.getBean("&routerUpdaterRules", BeanProxy.class)))
            .setImpl(new Visitor<RoutingInfo2Dispatcher>() {
                @Override
                public void visit(final RoutingInfo2Dispatcher rules) throws Exception {
                    cachedRouter.updateRouter(rules);
                }});
        
        final BeanProxy<HttpRequestTransformer.Builder> builderProxy = 
                ((BeanProxy<HttpRequestTransformer.Builder>) 
                    checkNotNull(ctx.getBean("&transformBuilder", BeanProxy.class)));
        builderProxy.setImpl(new CompositeAUPBuilder());
        
        ((BeanProxy<Visitor<CompositeAUPBuilder>>) checkNotNull(ctx.getBean("&aupUpdaterRules", BeanProxy.class)))
            .setImpl(new Visitor<CompositeAUPBuilder>() {
                @Override
                public void visit(final CompositeAUPBuilder builder) throws Exception {
                    builderProxy.setImplForced(builder);
                }});
        
        checkNotNull(ctx.getBean("routerUpdater", ZKUpdater.class)).start();
        checkNotNull(ctx.getBean("aupUpdater", ZKUpdater.class)).start();
        checkNotNull(ctx.getBean("gatewayUpdater", ZKUpdater.class)).start();
    }

}
