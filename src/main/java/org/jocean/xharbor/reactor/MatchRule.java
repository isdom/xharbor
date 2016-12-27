package org.jocean.xharbor.reactor;

import java.util.regex.Pattern;

import org.jocean.idiom.Regexs;

import io.netty.handler.codec.http.HttpRequest;

public class MatchRule implements Comparable<MatchRule> {
    
    public static final String X_ROUTE_CODE = "X-Route-Code";
    
    public MatchRule(
            final String methodPattern, 
            final String pathPattern,
            final String xroutePattern
            ) {
        this._methodPatternAsString = methodPattern;
        this._pathPatternAsString   = pathPattern;
        this._xroutePatternAsString = xroutePattern;
        this._methodPattern = Regexs.safeCompilePattern(this._methodPatternAsString);
        this._pathPattern = Regexs.safeCompilePattern(this._pathPatternAsString);
        this._xroutePattern = Regexs.safeCompilePattern(this._xroutePatternAsString);
    }
    
    public boolean match(final HttpRequest req) {
        return Regexs.isMatched(this._methodPattern, req.method().name())
            && Regexs.isMatched(this._pathPattern, req.uri()) 
            && Regexs.isMatched(this._xroutePattern, req.headers().get(X_ROUTE_CODE))
            ;
    }
    
    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("MatchRule [METHOD=").append(_methodPatternAsString)
                .append(", PATH=").append(_pathPatternAsString)
                .append(", XROUTE=").append(_xroutePatternAsString)
                .append("]");
        return builder.toString();
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
        result = prime * result + ((_xroutePatternAsString == null) ? 0
                : _xroutePatternAsString.hashCode());
        return result;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        MatchRule other = (MatchRule) obj;
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
        if (_xroutePatternAsString == null) {
            if (other._xroutePatternAsString != null)
                return false;
        } else if (!_xroutePatternAsString.equals(other._xroutePatternAsString))
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
        
        return compareTwoString(_xroutePatternAsString, o._xroutePatternAsString);
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
    private final String _xroutePatternAsString;
    
    private final Pattern _methodPattern;
    private final Pattern _pathPattern;
    private final Pattern _xroutePattern;
}
