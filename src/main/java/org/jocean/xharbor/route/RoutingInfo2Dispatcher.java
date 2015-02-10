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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jocean.idiom.Function;
import org.jocean.idiom.Pair;
import org.jocean.xharbor.api.Dispatcher;
import org.jocean.xharbor.api.Router;
import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.api.ServiceMemo;
import org.jocean.xharbor.util.RulesMXBean;
import org.jocean.xharbor.util.TargetSet;

/**
 * @author isdom
 *
 */
public class RoutingInfo2Dispatcher implements Cloneable, Router<RoutingInfo, Dispatcher>, RulesMXBean {

    private static final URI[] EMPTY_URIS = new URI[0];
    private static final Function<String, String> NOP_REWRITEPATH = new Function<String, String>() {
        @Override
        public String apply(final String input) {
            return input;
        }
        @Override
        public String toString() {
            return "NOP";
        }};
    private static final TargetSet EMPTY_TARGETSET = new TargetSet(EMPTY_URIS, NOP_REWRITEPATH, null);

    private static class MatchResult {
        final URI[] _uris;
        final Function<String, String> _rewritePath;
        
        MatchResult(final URI[] uris, final Function<String, String> rewritePath) {
            this._uris = uris;
            this._rewritePath = rewritePath;
        }
    }
    
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((_levels == null) ? 0 : _levels.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        RoutingInfo2Dispatcher other = (RoutingInfo2Dispatcher) obj;
        if (_levels == null) {
            if (other._levels != null)
                return false;
        } else if (!_levels.equals(other._levels))
            return false;
        return true;
    }

    @Override
    protected RoutingInfo2Dispatcher clone() throws CloneNotSupportedException {
        final RoutingInfo2Dispatcher cloned = new RoutingInfo2Dispatcher();
        for ( Level level : this._levels ) {
            cloned._levels.add(level.clone());
        }
        return cloned;
    }

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
    public Dispatcher calculateRoute(final RoutingInfo info, final Context routectx) {
        final ServiceMemo memo = routectx.getProperty("serviceMemo");
        final Iterator<Level> itr = _levels.iterator();
        while (itr.hasNext()) {
            final Level level = itr.next();
            final MatchResult result = level.match(info);
            if (null != result) {
                return new TargetSet(result._uris, result._rewritePath, memo);
            }
        }
        return EMPTY_TARGETSET;
    }

    public RoutingInfo2Dispatcher freeze() {
        this._isFrozen = true;
        return  this;
    }
    
    public RoutingInfo2Dispatcher addOrUpdateRewritePath(final int priority, final List<Pair<Pattern,String>> rewritePaths)
            throws Exception {
        if ( !this._isFrozen ) {
            getOrCreateLevel(priority).setRewritePaths(rewritePaths);
            return  this;
        } else {
            return this.clone().addOrUpdateRewritePath(priority, rewritePaths);
        }
    }
    
    public RoutingInfo2Dispatcher addOrUpdateRule(final int priority, final String uri, final RoutingInfo[] infoRegexs)
            throws Exception {
        if ( !this._isFrozen ) {
            getOrCreateLevel(priority).addOrUpdateRule(uri, infoRegexs);
            return  this;
        } else {
            return this.clone().addOrUpdateRule(priority, uri, infoRegexs);
        }
    }
    
    public RoutingInfo2Dispatcher removeRule(final int priority, final String uri) 
            throws Exception {
        if ( !this._isFrozen ) {
            getOrCreateLevel(priority).removeRule(uri);
            return  this;
        } else {
            return this.clone().removeRule(priority, uri);
        }
    }
    
    public RoutingInfo2Dispatcher removeLevel(final int priority) 
            throws Exception {
        if ( !this._isFrozen ) {
            doRemoveLevel(priority);
            return  this;
        } else {
            return this.clone().removeLevel(priority);
        }
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

    private void doRemoveLevel(final int priority) {
        final Iterator<Level> itr = this._levels.iterator();
        while (itr.hasNext()) {
            final Level level = itr.next();
            if ( level.getPriority() == priority ) {
                this._levels.remove(level);
            }
        }
    }

    static class Level implements Cloneable, Comparable<Level> {

        @Override
        public Level clone() throws CloneNotSupportedException {
            final Level cloned = new Level(this._priority);
            cloned._rules.putAll(this._rules);
            cloned._rewritePaths.addAll(this._rewritePaths);
            return cloned;
        }

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

        public void addOrUpdateRule(final String uri, final RoutingInfo[] infoRegexs) 
                throws Exception {
            final Pattern[] methodPatterns = new Pattern[infoRegexs.length];
            final Pattern[] pathPatterns = new Pattern[infoRegexs.length];
            for ( int idx = 0; idx < infoRegexs.length; idx++) {
                methodPatterns[idx] = safeCompilePattern(infoRegexs[idx].getMethod());
                pathPatterns[idx] = safeCompilePattern(infoRegexs[idx].getPath());
            }
            this._rules.put(new URI(uri), Pair.of(methodPatterns, pathPatterns));
        }

        public void removeRule(final String uri) throws Exception {
            this._rules.remove(new URI(uri));
        }

        public void setRewritePaths(final List<Pair<Pattern, String>> rewritePaths) {
            this._rewritePaths.clear();
            this._rewritePaths.addAll(rewritePaths);
        }
        
        /**
         * @param regex
         * @return
         */
        private static Pattern safeCompilePattern(final String regex) {
            return null != regex ? Pattern.compile(regex) : null;
        }
        
        private MatchResult match(final RoutingInfo info) {
            final List<URI> ret = new ArrayList<URI>();
            
            for ( Map.Entry<URI, Pair<Pattern[],Pattern[]>> entry : this._rules.entrySet() ) {
                final Pattern[] methodPatterns = entry.getValue().getFirst();
                final Pattern[] pathPatterns = entry.getValue().getSecond();
                for (int idx = 0; idx < methodPatterns.length; idx++) {
                    final Pattern methodPattern = methodPatterns[idx];
                    final Pattern pathPattern = pathPatterns[idx];
                    if ( isMatched(methodPattern, info.getMethod()) 
                            && isMatched(pathPattern, info.getPath()) ) {
                        ret.add(entry.getKey());
                        break;
                    }
                }
            }
            return !ret.isEmpty() 
                ? new MatchResult(ret.toArray(EMPTY_URIS), genRewritePath(info.getPath())) 
                : null;
        }

        /**
         * @param pattern
         * @param content
         * @return
         */
        private static boolean isMatched(final Pattern pattern, final String content) {
            return pattern != null ? pattern.matcher(content).find() : true;
        }
        
        private Function<String, String> genRewritePath(final String path) {
            for (Pair<Pattern,String> pair : this._rewritePaths) {
                final Pattern pattern = pair.getFirst();
                final Matcher matcher = pattern.matcher(path);
                if ( matcher.find() ) {
                    final String replaceTo = pair.getSecond();
                    return new Function<String, String>() {
                        @Override
                        public String apply(final String input) {
                            return pattern.matcher(input).replaceFirst(replaceTo);
                        }
                        
                        @Override
                        public String toString() {
                            return "[" + pattern.toString() + "->" + replaceTo + "]";
                        }};
                }
            }
            return NOP_REWRITEPATH;
        }
        
        private Collection<String> getRules() {
            return new ArrayList<String>() {
                private static final long serialVersionUID = 1L;
            {
                for ( Map.Entry<URI, Pair<Pattern[],Pattern[]>> entry : _rules.entrySet() ) {
                    final Pair<Pattern[],Pattern[]> pair = entry.getValue();
                    this.add(Integer.toString(_priority) + ":" + entry.getKey().toString() 
                            + ":methods" + Arrays.toString(pair.getFirst())
                            + ",paths" + Arrays.toString(pair.getSecond())
                    ); 
                }
            }};
        }
        
        private final int _priority;
        
        private final Map<URI, Pair<Pattern[],Pattern[]>> _rules = 
                new HashMap<URI, Pair<Pattern[],Pattern[]>>();
        
        private final List<Pair<Pattern,String>> _rewritePaths = 
                new ArrayList<Pair<Pattern,String>>();
    }

    private final SortedSet<Level> _levels = new TreeSet<Level>();
    private transient boolean _isFrozen = false;
}
