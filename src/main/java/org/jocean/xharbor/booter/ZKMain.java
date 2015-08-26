/**
 * 
 */
package org.jocean.xharbor.booter;

import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;


/**
 * @author isdom
 *
 */
public class ZKMain {
    /**
     * @param args
     * @throws Exception 
     */
    public static void main(String[] args) throws Exception {
        @SuppressWarnings({ "resource", "unused" })
        final AbstractApplicationContext ctx = 
                new ClassPathXmlApplicationContext("unit/zkbooter.xml");
    }
}
