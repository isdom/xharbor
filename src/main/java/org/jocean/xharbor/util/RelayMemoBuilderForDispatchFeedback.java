/**
 * 
 */
package org.jocean.xharbor.util;

import org.jocean.xharbor.api.RelayMemo;
import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.api.Target;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.util.Timer;

/**
 * @author isdom
 *
 */
public class RelayMemoBuilderForDispatchFeedback implements RelayMemo.Builder {

    private static final Logger LOG = LoggerFactory
            .getLogger(RelayMemoBuilderForDispatchFeedback.class);

    public RelayMemoBuilderForDispatchFeedback(final Timer timer) {
        this._timer = timer;
    }
    
    @Override
    public RelayMemo build(final Target target, final RoutingInfo info) {
        return new RelayMemo() {
            @Override
            public void beginBizStep(final STEP step) {
            }
            @Override
            public void endBizStep(final STEP step, final long ttl) {
                if ( step.equals(STEP.RECV_RESP) ) {
                    //  0ms <= ttl < 500 ms
                    if ( ttl >= 0 && ttl < 500 ) {
                        /*
                        final int weight = target.addWeight(1);
                        if ( LOG.isDebugEnabled() ) {
                            LOG.debug("endBizStep for RECV_RESP with ttl < 500ms, so add weight with 1 to {}",
                                    weight);
                        }
                        */
                    }
                }
            }
            @Override
            public void incBizResult(final RESULT result, final long ttl) {
                /*
                if (result.equals(RESULT.CONNECTDESTINATION_FAILURE)) {
                    markServiceDown4Result(60L, target, "CONNECTDESTINATION_FAILURE");
                }
                else if (result.equals(RESULT.RELAY_RETRY)) {
                    markAPIDown4Result(60L, target, info, "RELAY_RETRY");
                }
                else if (result.equals(RESULT.RELAY_FAILURE)) {
                    markAPIDown4Result(60L, target, info, "RELAY_FAILURE");
                }
                else if (result.equals(RESULT.HTTP_CLIENT_ERROR)) {
                    markAPIDown4Result(60L, target, info, "HTTP_CLIENT_ERROR");
                }
                else if (result.equals(RESULT.HTTP_SERVER_ERROR)) {
                    markAPIDown4Result(60L, target, info, "HTTP_SERVER_ERROR");
                }
                */
            }};
    }

    /*
    private void markAPIDown4Result(
            final long period, 
            final MarkableTarget target, 
            final RoutingInfo info, 
            final String result) {
        target.markAPIDownStatus(true);
        LOG.warn("relay failed for {}, so mark service {}'s API {} down.",
                result, target.serviceUri(), info);
        _timer.newTimeout(new TimerTask() {
            @Override
            public void run(final Timeout timeout) throws Exception {
                // reset down flag
                target.markAPIDownStatus(false);
                LOG.info("reset service {}'s API {} down flag after {} second cause by {}.",
                        target.serviceUri(), info, period, result);
            }
        }, period, TimeUnit.SECONDS);
    }

    private void markServiceDown4Result(
            final long period, 
            final MarkableTarget target,
            final String result) {
        target.markServiceDownStatus(true);
        LOG.warn("relay failed for {}, so mark service {} down.",
                result, target.serviceUri());
        _timer.newTimeout(new TimerTask() {
            @Override
            public void run(final Timeout timeout) throws Exception {
                // reset down flag
                target.markServiceDownStatus(false);
                LOG.info("reset service {} down flag after {} second cause by {}.",
                        target.serviceUri(), period, result);
            }
        }, period, TimeUnit.SECONDS);
    }
    */

    private final Timer _timer;
}
