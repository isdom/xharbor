/**
 * 
 */
package org.jocean.xharbor;

import static com.google.common.base.Preconditions.checkNotNull;

import org.jocean.idiom.Function;
import org.jocean.idiom.Pair;
import org.jocean.idiom.Visitor;
import org.jocean.j2se.spring.BeanProxy;
import org.jocean.xharbor.api.Dispatcher;
import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.route.CachedRouter;
import org.jocean.xharbor.route.RoutingInfo2Dispatcher;
import org.jocean.xharbor.spi.HttpRequestTransformer;
import org.jocean.xharbor.transform.CompositeAUPBuilder;
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
    }

}
