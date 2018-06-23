package org.jocean.xharbor.reactor;

import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import org.jocean.idiom.Pair;
import org.jocean.idiom.Regexs;

import com.google.common.base.Splitter;
import com.google.common.collect.Lists;

import io.netty.handler.codec.http.HttpRequest;
import rx.functions.Func1;

public class MatchRule implements Comparable<MatchRule> {

    public String pathPattern() {
        return this._pathPatternAsString;
    }

    public MatchRule(
            final String methodPattern,
            final String pathPattern,
            final String headersPattern
            ) {
        this._methodPatternAsString = methodPattern;
        this._pathPatternAsString   = pathPattern;
        this._headersPatternAsString = headersPattern;

        this._methodPattern = Regexs.safeCompilePattern(this._methodPatternAsString);
        this._pathPattern = Regexs.safeCompilePattern(this._pathPatternAsString);

        if (null != headersPattern && !headersPattern.isEmpty()) {
            final Iterator<String> iter = Splitter.on(',')
                    .trimResults()
                    .split(headersPattern)
                    .iterator();

            while (iter.hasNext()) {
                final String name = iter.next();
                if (!iter.hasNext()) {
                    break;
                }
                this._headersPredicates.add(Pair.of(name, buildPredicate(iter.next())));
            }
        }
    }

    private Func1<String, Boolean> buildPredicate(final String expression) {
        if ("==null".equals(expression)) {
            return value -> null == value;
        } else {
            final Pattern pattern = Regexs.safeCompilePattern(expression);
            return value -> Regexs.isMatched(pattern, value);
        }
    }

    public boolean match(final HttpRequest req) {
        final boolean matched = Regexs.isMatched(this._methodPattern, req.method().name())
            && Regexs.isMatched(this._pathPattern, req.uri());
        if (!matched) {
            return false;
        } else if (this._headersPredicates.isEmpty()) {
            return true;
        } else {
            for (final Pair<String, Func1<String, Boolean>> pair : this._headersPredicates) {
                final Func1<String, Boolean> predicate = pair.getSecond();
                final String value = req.headers().get(pair.getFirst());
                if (!predicate.call(value)) {
                    return false;
                }
            }
            return true;
        }
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("MatchRule [METHOD=").append(_methodPatternAsString)
                .append(", PATH=").append(_pathPatternAsString)
                .append(", HEADERS=").append(_headersPatternAsString)
                .append("]");
        return builder.toString();
    }

    public String summary() {
        return new StringBuilder()
                .append(_methodPatternAsString)
                .append(":").append(_pathPatternAsString)
                .append(":").append(_headersPatternAsString)
                .toString();
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((_methodPatternAsString == null) ? 0
                : _methodPatternAsString.hashCode());
        result = prime * result + ((_pathPatternAsString == null) ? 0
                : _pathPatternAsString.hashCode());
        result = prime * result + ((_headersPatternAsString == null) ? 0
                : _headersPatternAsString.hashCode());
        return result;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        final MatchRule other = (MatchRule) obj;
        if (_methodPatternAsString == null) {
            if (other._methodPatternAsString != null)
                return false;
        } else if (!_methodPatternAsString.equals(other._methodPatternAsString))
            return false;
        if (_pathPatternAsString == null) {
            if (other._pathPatternAsString != null)
                return false;
        } else if (!_pathPatternAsString.equals(other._pathPatternAsString))
            return false;
        if (_headersPatternAsString == null) {
            if (other._headersPatternAsString != null)
                return false;
        } else if (!_headersPatternAsString.equals(other._headersPatternAsString))
            return false;
        return true;
    }

    @Override
    public int compareTo(final MatchRule o) {
        if (this == o)
            return 0;

        int order;

        order = compareTwoString(_methodPatternAsString, o._methodPatternAsString);
        if ( 0 != order) {
            return order;
        }

        order = compareTwoString(_pathPatternAsString, o._pathPatternAsString);
        if ( 0 != order) {
            return order;
        }

        return compareTwoString(_headersPatternAsString, o._headersPatternAsString);
    }

    private static int compareTwoString(final String str1, final String str2) {
        if (str1 == null) {
            if (str2 != null)
                return -1;
        } else {
            return str1.compareTo(str2);
        }
        return 0;
    }

    private final String _methodPatternAsString;
    private final String _pathPatternAsString;
    private final String _headersPatternAsString;

    private final Pattern _methodPattern;
    private final Pattern _pathPattern;
    private final List<Pair<String,Func1<String, Boolean>>> _headersPredicates = Lists.newArrayList();
}
