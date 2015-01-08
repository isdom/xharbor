/**
 * 
 */
package org.jocean.xharbor.util;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author isdom
 *
 */
public class TimeInterval10ms_100ms_500ms_1s_5sImpl implements
        TimeIntervalMemo,
        TimeInterval10ms_100ms_500ms_1s_5sMXBean {

    @Override
    public void recordInterval(final long interval) {
        final int idx = interval2idx(interval);
        if ( idx >= 0) {
            this._counters[idx].incrementAndGet();
        }
    }
    
    @Override
    public int get1_lt10ms() {
        return this._counters[0].get();
    }

    @Override
    public int get2_lt100ms() {
        return this._counters[1].get();
    }

    @Override
    public int get3_lt500ms() {
        return this._counters[2].get();
    }

    @Override
    public int get4_lt1s() {
        return this._counters[3].get();
    }

    @Override
    public int get5_lt5s() {
        return this._counters[4].get();
    }

    @Override
    public int get6_mt5s() {
        return this._counters[5].get();
    }

    private int interval2idx(final long interval) {
        if ( interval < 0L ) {
            return -1;
        }
        else if ( interval < 10L ) {
            return  0;
        }
        else if ( interval < 100L ) {
            return  1;
        }
        else if ( interval < 500L ) {
            return  2;
        }
        else if (interval < 1000L ) {
            return  3;
        }
        else if (interval < 5000L ) {
            return  4;
        }
        else {
            return  5;
        }
    }
    
    private final AtomicInteger[] _counters = new AtomicInteger[]{
            new AtomicInteger(0),
            new AtomicInteger(0),
            new AtomicInteger(0),
            new AtomicInteger(0),
            new AtomicInteger(0),
            new AtomicInteger(0)
            };
}
