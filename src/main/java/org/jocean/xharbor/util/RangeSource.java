/**
 * 
 */
package org.jocean.xharbor.util;

import com.google.common.collect.Range;

/**
 * @author isdom
 *
 */
public interface RangeSource<C extends Comparable<C>> {
    public Range<C> range();
}
