/**
 * 
 */
package org.jocean.xharbor.util;

import java.net.URI;
import java.util.List;

import org.jocean.idiom.Function;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.SimpleCache;
import org.jocean.idiom.Tuple;
import org.jocean.idiom.Visitor2;
import org.jocean.idiom.stats.TimeIntervalMemo;
import org.jocean.j2se.stats.BizMemoSupportMBean;
import org.jocean.j2se.stats.TIMemoImplOfRanges;
import org.jocean.xharbor.api.RelayMemo;
import org.jocean.xharbor.api.RelayMemo.RESULT;
import org.jocean.xharbor.api.RelayMemo.STEP;
import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.api.Target;

import com.google.common.collect.Range;

/**
 * @author isdom
 *
 */
public class RelayMemoBuilderForStats implements RelayMemo.Builder {

    public RelayMemoBuilderForStats(final Visitor2<String, InfoListMaker> register) throws Exception {
        this._register = register;
        this._level0Memo = new RelayMemoImpl()
        .fillTimeIntervalMemoWith(new Function<Enum<?>, TimeIntervalMemo>() {
            @Override
            public TimeIntervalMemo apply(final Enum<?> e) {
                return _ttlMemos.get(Tuple.of(e));
            }});
        this._register.visit("all", this._level0Memo);
    }
    
    @Override
    public RelayMemo build(final Target target, final RoutingInfo info) {
        return InterfaceUtils.combineImpls(
            RelayMemo.class, 
            this._level0Memo,
            this._bizMemos.get(Tuple.of(normalizeString(info.getPath()))),
            this._bizMemos.get(Tuple.of(normalizeString(info.getPath()), info.getMethod())),
            this._bizMemos.get(Tuple.of(normalizeString(info.getPath()), info.getMethod(), 
                    uri2value(target.serviceUri())))
            );
    }

    private static final String[] _OBJNAME_KEYS = new String[]{"path", "method", "dest"};

    private static final String normalizeString(final String input) {
        return input.replaceAll(":", "-");
    }
    
    private static final String uri2value(final URI uri) {
        return normalizeString(uri.toString());
    }
    
    private static class TIMemoImpl extends TIMemoImplOfRanges
        implements InfoListMaker {
        
        @Override
        public void addInfoList(final List<String> infos) {
            for ( int idx = 0; idx < this._names.length; idx++ ) {
                infos.add(this._names[idx] +":"+ this._counters[idx].get());
            }
        }
        private static final Range<Long> MT30S = Range.atLeast(30000L);
        private static final Range<Long> LT30S = Range.closedOpen(10000L, 30000L);
        private static final Range<Long> LT10S = Range.closedOpen(5000L, 10000L);
        private static final Range<Long> LT5S = Range.closedOpen(1000L, 5000L);
        private static final Range<Long> LT1S = Range.closedOpen(500L, 1000L);
        private static final Range<Long> LT500MS = Range.closedOpen(100L, 500L);
        private static final Range<Long> LS100MS = Range.closedOpen(10L, 100L);
        private static final Range<Long> LT10MS = Range.closedOpen(0L, 10L);

        @SuppressWarnings("unchecked")
        public TIMemoImpl() {
              super(new String[]{
                      "lt10ms",
                      "lt100ms",
                      "lt500ms",
                      "lt1s",
                      "lt5s",
                      "lt10s",
                      "lt30s",
                      "mt30s",
                      },
                      new Range[]{
                      LT10MS,
                      LS100MS,
                      LT500MS,
                      LT1S,
                      LT5S,
                      LT10S,
                      LT30S,
                      MT30S});
          }
    }
    
    private static class RelayMemoImpl extends BizMemoSupportMBean<RelayMemoImpl, STEP, RESULT> 
        implements RelayMemo, InfoListMaker {
        
        public RelayMemoImpl() {
            super(STEP.class, RESULT.class);
        }
        
        @Override
        public void addInfoList(final List<String> infos) {
            for (STEP step : this._steps) {
                infos.add("STEP:" + step.name() +":"+ this._stepCounters[step.ordinal()].get());
            }
            for (RESULT result : this._results) {
                infos.add("RESULT:" + result.name() +":"+ this._resultCounters[result.ordinal()].get());
            }
        }
    }
    
    private final Visitor2<String, InfoListMaker> _register;
    
    private Function<Tuple, TIMemoImpl> _ttlMemoMaker = new Function<Tuple, TIMemoImpl>() {
        @Override
        public TIMemoImpl apply(final Tuple tuple) {
            return new TIMemoImpl();
        }};
        
    private Visitor2<Tuple, TIMemoImpl> _ttlMemoRegister = new Visitor2<Tuple, TIMemoImpl>() {
        @Override
        public void visit(final Tuple tuple, final TIMemoImpl newMemo) throws Exception {
            final StringBuilder sb = new StringBuilder();
            Character splitter = null;
            //                      for last Enum<?>
            for ( int idx = 0; idx < tuple.size()-1; idx++) {
                if (null != splitter) {
                    sb.append(splitter);
                }
                sb.append(_OBJNAME_KEYS[idx]);
                sb.append("=");
                sb.append((String)tuple.getAt(idx));
                splitter = ',';
            }
            final Enum<?> stepOrResult = tuple.getAt(tuple.size()-1);
            final String category = stepOrResult.getClass().getSimpleName();
            final String ttl = stepOrResult.name();
            if (null != splitter) {
                sb.append(splitter);
            }
            sb.append("category=");
            sb.append(category);
            sb.append(',');
            sb.append("ttl=");
            sb.append(ttl);
            
            if ( null!=_register) {
                _register.visit(sb.toString(), newMemo);
            }
        }};
            
    private SimpleCache<Tuple, TIMemoImpl> _ttlMemos  = 
            new SimpleCache<Tuple, TIMemoImpl>(this._ttlMemoMaker, this._ttlMemoRegister);
    
    private final Function<Tuple, RelayMemoImpl> _bizMemoMaker = 
            new Function<Tuple, RelayMemoImpl>() {
        @Override
        public RelayMemoImpl apply(final Tuple tuple) {
            return new RelayMemoImpl()
                .fillTimeIntervalMemoWith(new Function<Enum<?>, TimeIntervalMemo>() {
                    @Override
                    public TimeIntervalMemo apply(final Enum<?> e) {
                        return _ttlMemos.get(tuple.append(e));
                    }});
        }};

    private final Visitor2<Tuple, RelayMemoImpl> _bizMemoRegister = 
            new Visitor2<Tuple, RelayMemoImpl>() {
        @Override
        public void visit(final Tuple tuple, final RelayMemoImpl newMemo)
                throws Exception {
            final StringBuilder sb = new StringBuilder();
            Character splitter = null;
            for ( int idx = 0; idx < tuple.size(); idx++) {
                if (null != splitter) {
                    sb.append(splitter);
                }
                sb.append(_OBJNAME_KEYS[idx]);
                sb.append("=");
                sb.append((String)tuple.getAt(idx));
                splitter = ',';
            }
            if ( null!=_register) {
                _register.visit(sb.toString(), newMemo);
            }
        }};
        
    private final SimpleCache<Tuple, RelayMemoImpl> _bizMemos = 
            new SimpleCache<Tuple, RelayMemoImpl>(this._bizMemoMaker, this._bizMemoRegister);
    
    private final RelayMemoImpl _level0Memo;
}
