package org.jocean.xharbor.routing.impl;

import io.netty.handler.codec.http.HttpRequest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.routing.RewriteRequestRule;
import org.jocean.xharbor.routing.RuleSet;

import rx.functions.Action1;

public class RequestPathRewriter implements RewriteRequestRule {
    public RequestPathRewriter(
            final RuleSet rules,
            final String pathPattern, 
            final String replaceTo) {
        this._rules = rules;
        this._pathPattern = safeCompilePattern(pathPattern);
        this._replaceTo = replaceTo;
        this._rules.addRequestRewriter(this);
    }
    
    public void stop() {
        this._rules.removeRequestRewriter(this);
    }
    
    @Override
    public Action1<HttpRequest> genRewriting(final RoutingInfo info) {
        final Matcher matcher = this._pathPattern.matcher(info.getPath());
        if ( matcher.find() ) {
            return new Action1<HttpRequest>() {
                @Override
                public void call(final HttpRequest request) {
                    request.setUri(
                        _pathPattern.matcher(request.getUri())
                            .replaceFirst(_replaceTo));
                }
                
                @Override
                public String toString() {
                    return "[" + _pathPattern.toString() + "->" + _replaceTo + "]";
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
    private final String _replaceTo;
}
