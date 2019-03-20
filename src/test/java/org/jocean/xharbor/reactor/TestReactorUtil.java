package org.jocean.xharbor.reactor;

import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.idiom.StopWatch;
import org.jocean.xharbor.api.TradeReactor.ReactContext;

import io.opentracing.Span;
import io.opentracing.Tracer;
import rx.Scheduler;

class TestReactorUtil {
    static ReactContext nullctx() {
        return new ReactContext() {

            @Override
            public HttpTrade trade() {
                return null;
            }

            @Override
            public StopWatch watch() {
                return null;
            }

            @Override
            public Tracer tracer() {
                return null;
            }

            @Override
            public Span span() {
                return null;
            }

            @Override
            public Scheduler scheduler() {
                return null;
            }

            @Override
            public int concurrent() {
                return 0;
            }};
    }
}
