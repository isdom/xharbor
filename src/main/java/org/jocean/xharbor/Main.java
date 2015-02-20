/**
 * 
 */
package org.jocean.xharbor;

import static com.google.common.base.Preconditions.checkNotNull;

import org.jocean.idiom.Visitor;
import org.jocean.j2se.spring.BeanProxy;
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
//    private static final String normalizeString(final String input) {
//        return input.replaceAll(":", "-");
//    }
    
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
        
        checkNotNull(ctx.getBean("aupUpdater", ZKUpdater.class)).start();
//        checkNotNull(ctx.getBean("gatewayUpdater", ZKUpdater.class)).start();
        checkNotNull(ctx.getBean("unitUpdater", ZKUpdater.class)).start();
    }

}
