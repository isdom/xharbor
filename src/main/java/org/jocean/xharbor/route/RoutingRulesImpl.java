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

public class RoutingRulesImpl implements RoutingRules {
    
    @Override
    public String[] getRules() {
        return new ArrayList<String>() {
            private static final long serialVersionUID = 1L;
        {
            final Iterator<Level> itr = _levels.iterator();
            while (itr.hasNext()) {
                this.addAll(itr.next().getRules());
            }
        }}.toArray(new String[0]);
    }

    public void addRule(final int priority, final URI uri, final Pattern[] patterns) {
        getOrCreateLevel(priority).addRule(uri, patterns);
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

    @Override
    public URI[] calculateRoute(final String path) {
        final Iterator<Level> itr = _levels.iterator();
        while (itr.hasNext()) {
            final Level level = itr.next();
            final URI[] uris = level.match(path);
            if ( null != uris && uris.length > 0 ) {
                return uris;
            }
        }
        return new URI[0];
    }
    
    private static class Level implements Comparable<Level> {

        public Level(final int priority) {
            this._priority = priority;
        }
        
        @Override
        public int compareTo(final Level o) {
            return o._priority - this._priority;
        }
        
        
        public int getPriority() {
            return this._priority;
        }

        public void addRule(final URI uri, final Pattern[] patterns) {
            this._rules.put(uri, patterns);
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
            return ret.toArray(new URI[0]);
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
