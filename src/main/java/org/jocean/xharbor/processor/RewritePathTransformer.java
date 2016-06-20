package org.jocean.xharbor.processor;

import java.util.regex.Pattern;

import org.jocean.xharbor.api.HttpMessageTransformer;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import rx.Observable;
import rx.functions.Func1;

public class RewritePathTransformer implements HttpMessageTransformer {

    public RewritePathTransformer(
            final String pathPattern, 
            final String replaceTo) {
        this._pathPattern = safeCompilePattern(pathPattern);
        this._replaceTo = replaceTo;
    }
    
    @Override
    public Observable<HttpObject> call(final Observable<HttpObject> source) {
        return source.map(this._replacer);
    }

    private static Pattern safeCompilePattern(final String regex) {
        return null != regex && !"".equals(regex) ? Pattern.compile(regex) : null;
    }
    
    private final Pattern _pathPattern;
    private final String _replaceTo;
    private final Func1<HttpObject, HttpObject> _replacer = new Func1<HttpObject, HttpObject>() {

        @Override
        public HttpObject call(final HttpObject msg) {
            if (msg instanceof HttpRequest) {
                final HttpRequest orgreq = (HttpRequest)msg;
                final DefaultHttpRequest newreq = new DefaultHttpRequest(orgreq.getProtocolVersion(), 
                        orgreq.getMethod(), orgreq.getUri(), true);
                newreq.headers().set(orgreq.headers());
                
                return newreq.setUri(
                    _pathPattern.matcher(orgreq.getUri())
                        .replaceFirst(_replaceTo));
            } else {
                return msg;
            }
        }};
}
