/**
 * 
 */
package org.jocean.xharbor.route;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.jocean.xharbor.spi.Router;

/**
 * @author isdom
 *
 */
public class Path2URIsRouter implements Router<String, URI[]>, RulesMXBean {

    private static final URI[] EMPTY_URIS = new URI[0];

    @Override
    public String[] getRoutingRules() {
        return new ArrayList<String>() {
            private static final long serialVersionUID = 1L;
        {
            final Iterator<Level> itr = _levels.iterator();
            while (itr.hasNext()) {
                this.addAll(itr.next().getRules());
            }
        }}.toArray(new String[0]);
    }
    
    @Override
    public URI[] calculateRoute(final String path, final Context routectx) {
        final Iterator<Level> itr = _levels.iterator();
        while (itr.hasNext()) {
            final Level level = itr.next();
            final URI[] uris = level.match(path);
            if ( null != uris && uris.length > 0 ) {
                return uris;
            }
        }
        return EMPTY_URIS;
    }

    public void addRule(final int priority, final String uri, final String[] regexs) 
            throws Exception {
        getOrCreateLevel(priority).addRule(uri, regexs);
    }
    
    private Level getOrCreateLevel(final int priority) {
        final Iterator<Level> itr = this._levels.iterator();
        while (itr.hasNext()) {
            final Level level = itr.next();
            if ( level.getPriority() == priority ) {
                return level;
            }
        }
        final Level level = new Level(priority);
        this._levels.add(level);
        return level;
    }

    private static class Level implements Comparable<Level> {

        public Level(final int priority) {
            this._priority = priority;
        }
        
        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + _priority;
            result = prime * result
                    + ((_rules == null) ? 0 : _rules.hashCode());
            return result;
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            Level other = (Level) obj;
            if (_priority != other._priority)
                return false;
            if (_rules == null) {
                if (other._rules != null)
                    return false;
            } else if (!_rules.equals(other._rules))
                return false;
            return true;
        }

        @Override
        public int compareTo(final Level o) {
            return o._priority - this._priority;
        }
        
        
        public int getPriority() {
            return this._priority;
        }

        public void addRule(final String uri, final String[] regexs) throws Exception {
            final Pattern[] patterns = new Pattern[regexs.length];
            for ( int idx = 0; idx < regexs.length; idx++) {
                patterns[idx] = Pattern.compile(regexs[idx]);
            }
            this._rules.put(new URI(uri), patterns);
        }
        
        private URI[] match(final String path) {
            final List<URI> ret = new ArrayList<URI>();
            
            for ( Map.Entry<URI, Pattern[]> entry : this._rules.entrySet() ) {
                final Pattern[] patterns = entry.getValue();
                for (Pattern p : patterns) {
                    if ( p.matcher(path).find() ) {
                        ret.add(entry.getKey());
                    }
                }
            }
            return ret.toArray(EMPTY_URIS);
        }
        
        private Collection<String> getRules() {
            return new ArrayList<String>() {
                private static final long serialVersionUID = 1L;
            {
                for ( Map.Entry<URI, Pattern[]> entry : _rules.entrySet() ) {
                    final Pattern[] patterns = entry.getValue();
                    this.add(Integer.toString(_priority) + ":" + entry.getKey().toString() + ":" + Arrays.toString(patterns)); 
                }
            }};
        }
        
        private final int _priority;
        
        private final Map<URI, Pattern[]> _rules = new HashMap<URI, Pattern[]>();

    }

    private final SortedSet<Level> _levels = new TreeSet<Level>();
}
