package org.jocean.xharbor.route;

import static org.junit.Assert.*;

import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.route.RoutingInfo2Dispatcher.Level;
import org.junit.Test;

public class RoutingInfo2DispatcherTestCase {

    @Test
    public void testLevelClone() throws Exception {
        final Level l1 = new Level(1);
        
        l1.addOrUpdateRule("http://127.0.0.1", new RoutingInfo[]{new RoutingInfo() {

            @Override
            public String getMethod() {
                return null;
            }

            @Override
            public String getPath() {
                return null;
            }}});
        
        final Level l2 = l1.clone();
        assertEquals(l1, l2);
        
        l2.addOrUpdateRule("http://www.sina.com", new RoutingInfo[]{new RoutingInfo() {

            @Override
            public String getMethod() {
                return null;
            }

            @Override
            public String getPath() {
                return null;
            }}});
        assertNotEquals(l1, l2);
    }
}
