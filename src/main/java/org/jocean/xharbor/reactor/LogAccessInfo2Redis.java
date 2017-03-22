package org.jocean.xharbor.reactor;

import java.util.Map;

import org.jocean.http.util.RxNettys;
import org.jocean.idiom.BeanHolder;
import org.jocean.idiom.BeanHolderAware;
import org.jocean.idiom.Ordered;
import org.jocean.idiom.Pair;
import org.jocean.redis.RedisClient;
import org.jocean.redis.RedisUtil;
import org.jocean.xharbor.api.TradeReactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.google.common.collect.Maps;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.redis.RedisMessage;
import rx.Observable;
import rx.Single;
import rx.functions.Action1;
import rx.functions.Func1;

public class LogAccessInfo2Redis implements TradeReactor, Ordered, BeanHolderAware {
    
    private static final Logger LOG = LoggerFactory
            .getLogger(LogAccessInfo2Redis.class);
    
    @Override
    public void setBeanHolder(final BeanHolder beanHolder) {
        this._beanHolder = beanHolder;
    }
    
    @Override
    public Single<? extends InOut> react(final ReactContext ctx, final InOut io) {
        if ( null != this._beanHolder) {
            final RedisClient redisclient = 
                    _beanHolder.getBean(RedisClient.class);
            if (null != redisclient) {
                return tryLogtoRedis(ctx, io, redisclient);
            }
        }
        return Single.<InOut>just(null);
        
    }
    
    private Single<? extends InOut> tryLogtoRedis(
            final ReactContext ctx, 
            final InOut io,
            final RedisClient redisclient) {
        if (null != io.inbound() && null != io.outbound()) {
            io.inbound().compose(RxNettys.asHttpRequest())
            .flatMap(new Func1<HttpRequest, Observable<Pair<HttpRequest, HttpResponse>>>() {
                @Override
                public Observable<Pair<HttpRequest, HttpResponse>> call(
                        final HttpRequest req) {
                    return io.outbound().compose(RxNettys.asHttpResponse())
                        .flatMap(new Func1<HttpResponse, Observable<Pair<HttpRequest, HttpResponse>>>() {
                        @Override
                        public  Observable<Pair<HttpRequest, HttpResponse>> call(
                                final HttpResponse resp) {
                            return Observable.just(Pair.of(req, resp));
                        }});
                }})
            .flatMap(new Func1<Pair<HttpRequest, HttpResponse>, Observable<RedisMessage>>() {
                @SuppressWarnings("unchecked")
                @Override
                public Observable<RedisMessage> call(final Pair<HttpRequest, HttpResponse> reqAndResp) {
                    final HttpRequest req = reqAndResp.first;
                    final HttpResponse resp = reqAndResp.second;
                    final String remoteip = reqAndResp.first.headers().get("remoteip");
                    final Map<String, Object> data = Maps.newHashMap();
                    data.put("method", req.method().name());
                    data.put("uri", req.uri());
                    data.put("reqContentType", req.headers().get(HttpHeaderNames.CONTENT_TYPE));
                    data.put("reqContentLength", req.headers().get(HttpHeaderNames.CONTENT_LENGTH));
                    data.put("status", resp.status().code());
                    data.put("respContentType", resp.headers().get(HttpHeaderNames.CONTENT_TYPE));
                    data.put("respContentLength", resp.headers().get(HttpHeaderNames.CONTENT_LENGTH));
                    
                    if (null != remoteip) {
                        return redisclient.getConnection()
                            .compose(RedisUtil.interactWithRedis(
                                    RedisUtil.cmdSet(remoteip, JSON.toJSONString(data)).build() ));
                    } else {
                        return Observable.<RedisMessage>empty();
                    }
                }})
            .subscribe(new Action1<RedisMessage>() {
                @Override
                public void call(final RedisMessage redismsg) {
                    LOG.info("trade log to redis with {}", RedisUtil.dumpAggregatedRedisMessage(redismsg));
                }})
            ;
        }
        return Single.<InOut>just(null);
    }

    @Override
    public int ordinal() {
        return 0;
    }
    
    private BeanHolder _beanHolder;
}
