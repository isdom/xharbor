package org.jocean.xharbor.relay;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jocean.idiom.BeanFinder;
import org.springframework.beans.factory.annotation.Value;

import rx.Observable.Transformer;
import rx.Scheduler;
import rx.schedulers.Schedulers;

public class TradeExecutor {

    public static <T> Transformer<T, T> observeOn(final BeanFinder finder, final String tpname, final int bufferSize) {
        return ts -> finder.find(tpname, TradeExecutor.class).flatMap(executor -> ts.observeOn(executor._workerScheduler, bufferSize));
    }

    void start() {
        this._workers = Executors.newFixedThreadPool(this._workerCount);
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

    ExecutorService _workers;
    Scheduler _workerScheduler;
}
