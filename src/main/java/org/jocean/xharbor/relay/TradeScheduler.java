package org.jocean.xharbor.relay;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.idiom.BeanFinder;
import org.springframework.beans.factory.annotation.Value;

import rx.Observable.Transformer;
import rx.Scheduler;
import rx.schedulers.Schedulers;

public class TradeScheduler {

    public static <T> Transformer<T, T> observeOn(final BeanFinder finder, final String tpname, final int bufferSize) {
        return ts -> finder.find(tpname, TradeScheduler.class).flatMap(executor -> ts.observeOn(executor._workerScheduler, bufferSize));
    }

    static class DefaultThreadFactory implements ThreadFactory {
        private final ThreadGroup _group;
        private final AtomicInteger _threadNumber = new AtomicInteger(1);
        private final String _namePrefix;

        DefaultThreadFactory(final String prefix) {
            final SecurityManager s = System.getSecurityManager();
            _group = (s != null) ? s.getThreadGroup() :
                                  Thread.currentThread().getThreadGroup();
            _namePrefix = prefix + "-";
        }

        @Override
        public Thread newThread(final Runnable r) {
            final Thread t = new Thread(_group, r,
                                  _namePrefix + _threadNumber.getAndIncrement(),
                                  0);
            if (t.isDaemon())
                t.setDaemon(false);
            if (t.getPriority() != Thread.NORM_PRIORITY)
                t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }

    void start() {
        this._workers = Executors.newFixedThreadPool(this._workerCount, new DefaultThreadFactory(_threadName));
        this._workerScheduler = Schedulers.from(this._workers);
    }

    void stop() {
        this._workers.shutdown();
    }

    public Scheduler scheduler() {
        return this._workerScheduler;
    }

    @Value("${worker.count}")
    int _workerCount = 2;

    @Value("${thread.name}")
    String _threadName = "trade-worker";

    ExecutorService _workers;
    Scheduler _workerScheduler;
}
