package org.jocean.xharbor.api;

import org.jocean.http.FullMessage;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.idiom.StopWatch;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.opentracing.Span;
import io.opentracing.Tracer;
import rx.Observable;
import rx.Scheduler;
import rx.Single;

public interface TradeReactor {
    public interface ReactContext {
        public HttpTrade trade();
        public StopWatch watch();
        public Tracer tracer();
        public Span span();
        public Scheduler scheduler();
        public int concurrent();
    }

    public interface InOut {
        public Observable<FullMessage<HttpRequest>> inbound();
        public Observable<FullMessage<HttpResponse>> outbound();
    }

    public Single<Boolean> match(final ReactContext ctx, final InOut io);

    public Single<? extends InOut> react(final ReactContext ctx, final InOut io);
}
