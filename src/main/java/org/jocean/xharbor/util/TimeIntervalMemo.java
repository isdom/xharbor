/**
 * 
 */
package org.jocean.xharbor.util;

/**
 * @author isdom
 *
 */
public interface TimeIntervalMemo {
    public void recordInterval(final long interval);
    
    public static TimeIntervalMemo NOP = new TimeIntervalMemo() {
        @Override
        public void recordInterval(final long interval) {
        }};
}
