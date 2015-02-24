/**
 * 
 */
package org.jocean.xharbor;

import static com.google.common.base.Preconditions.checkNotNull;

import org.jocean.xharbor.util.ZKUpdater;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * @author isdom
 *
 */
public class Main {
    
    public static void main(String[] args) throws Exception {
        
        @SuppressWarnings("resource")
        final AbstractApplicationContext ctx =
                new ClassPathXmlApplicationContext(
                        new String[]{"xharbor.xml"});
        
        checkNotNull(ctx.getBean("unitUpdater", ZKUpdater.class)).start();
    }

}
