/**
 * 
 */
package org.jocean.httpgateway.biz;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.jocean.idiom.SimpleCache;
import org.jocean.idiom.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class DefaultDispatcher implements HttpRequestDispatcher {
    private static final Logger LOG = LoggerFactory
            .getLogger(DefaultDispatcher.class);

    public DefaultDispatcher() {
        this._cache = new SimpleCache<String, URI[]>(new Function<String, URI[]>() {
            public URI[] apply(final String path) {
                final Iterator<RuleSet> itr = _rules.iterator();
                while (itr.hasNext()) {
                    final RuleSet rs = itr.next();
                    final URI[] uris = rs.match(path);
                    if ( null != uris && uris.length > 0 ) {
                        return uris;
                    }
                }
                return new URI[0];
            }
        });
    }
    
    @Override
    public URI dispatch(final HttpRequest request) {
        final QueryStringDecoder decoder = new QueryStringDecoder(request.getUri());

        final String path = decoder.path();
        if ( LOG.isDebugEnabled()) {
            LOG.debug("dispatch for path:{}", path);
        }
        final URI[] uris = this._cache.get(path);
        if (uris != null && uris.length > 0) {
            return uris[(int)(Math.random() * uris.length)];
        }
        else {
            return null;
        }
    }

    public void addRuleSet(final RuleSet rs) {
        this._rules.add(rs);
    }
    
    public static class RuleSet implements Comparable<RuleSet> {

        public RuleSet(final int priority) {
            this._priority = priority;
        }
        
        @Override
        public int compareTo(final RuleSet o) {
            return o._priority - this._priority;
        }
        
        public void addTarget(final URI uri, final Pattern[] patterns) {
            this._targets.put(uri, patterns);
        }
        
        public URI[] match(final String path) {
            final List<URI> ret = new ArrayList<URI>();
            
            for ( Map.Entry<URI, Pattern[]> entry : this._targets.entrySet() ) {
                final Pattern[] patterns = entry.getValue();
                for (Pattern p : patterns) {
                    if ( p.matcher(path).find() ) {
                        ret.add(entry.getKey());
                    }
                }
            }
            return ret.toArray(new URI[0]);
        }
        
        private final int _priority;
        
        private final Map<URI, Pattern[]> _targets = new HashMap<URI, Pattern[]>();
    }
    
    private SortedSet<RuleSet> _rules = new TreeSet<RuleSet>();
    
    private final SimpleCache<String, URI[]> _cache;
}
