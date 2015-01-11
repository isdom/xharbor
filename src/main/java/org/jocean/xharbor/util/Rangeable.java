/**
 * 
 */
package org.jocean.xharbor.util;

import com.google.common.collect.Range;

/**
 * @author isdom
 *
 */
public interface Rangeable<C extends Comparable<C>> {
    public Range<C> range();
}
