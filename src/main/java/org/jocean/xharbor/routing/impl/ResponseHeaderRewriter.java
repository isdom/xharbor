package org.jocean.xharbor.routing.impl;

import io.netty.handler.codec.http.HttpResponse;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.routing.RewriteResponseRule;
import org.jocean.xharbor.routing.RuleSet;

import rx.functions.Action1;

public class ResponseHeaderRewriter implements RewriteResponseRule {
    public ResponseHeaderRewriter(
            final RuleSet rules,
            final String pathPattern,
            final Map<String, String> extraHeaders) {
        this._rules = rules;
        this._pathPattern = safeCompilePattern(pathPattern);
        this._extraHeaders = extraHeaders;
        this._rules.addResponseRewriter(this);
    }
    
    public void stop() {
        this._rules.removeResponseRewriter(this);
    }
    
    @Override
    public Action1<HttpResponse> genRewriting(final RoutingInfo info) {
        final Matcher matcher = this._pathPattern.matcher(info.getPath());
        if ( matcher.find() ) {
            return new Action1<HttpResponse>() {
                @Override
                public void call(final HttpResponse response) {
                    for (Map.Entry<String, String> entry : _extraHeaders.entrySet()) {
                        response.headers().add(entry.getKey(), entry.getValue());
                    }
                }
                
                @Override
                public String toString() {
                    return "[" + _pathPattern.toString() + "-> headers ]";
                }};
        } else {
            return null;
        }
    }
    
    private static Pattern safeCompilePattern(final String regex) {
        return null != regex && !"".equals(regex) ? Pattern.compile(regex) : null;
    }
    
    private final RuleSet _rules;
    private final Pattern _pathPattern;
    private final Map<String, String> _extraHeaders;
}
