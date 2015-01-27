/**
 * 
 */
package org.jocean.xharbor.util;

import java.net.URI;

import org.jocean.idiom.Function;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.SimpleCache;
import org.jocean.idiom.Tuple;
import org.jocean.idiom.Visitor2;
import org.jocean.j2se.MBeanRegisterSupport;
import org.jocean.xharbor.spi.RelayMemo;
import org.jocean.xharbor.spi.RelayMemo.RESULT;
import org.jocean.xharbor.spi.RelayMemo.STEP;
import org.jocean.xharbor.spi.RoutingInfo;
import org.jocean.xharbor.spi.Target;

import com.google.common.collect.Range;

/**
 * @author isdom
 *
 */
public class RelayMemoBuilderForStats implements RelayMemo.Builder {

    public RelayMemoBuilderForStats() {
        this._mbeanSupport.registerMBean("name=relays", this._level0Memo.createMBean());
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
    
    private static class RelayTIMemoImpl extends TIMemoImplOfRanges {
        
        @SuppressWarnings("unchecked")
        public RelayTIMemoImpl() {
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
                      Range.closedOpen(0L, 10L),
                      Range.closedOpen(10L, 100L),
                      Range.closedOpen(100L, 500L),
                      Range.closedOpen(500L, 1000L),
                      Range.closedOpen(1000L, 5000L),
                      Range.closedOpen(5000L, 10000L),
                      Range.closedOpen(10000L, 30000L),
                      Range.atLeast(30000L)});
          }
    }
    
    private static class RelayMemoImpl extends BizMemoImpl<RelayMemoImpl, STEP, RESULT> 
        implements RelayMemo {
        
        public RelayMemoImpl() {
            super(STEP.class, RESULT.class);
        }
    }
    
    private final MBeanRegisterSupport _mbeanSupport = 
            new MBeanRegisterSupport("org.jocean:type=router", null);
    
    private Function<Tuple, RelayTIMemoImpl> _ttlMemoMaker = new Function<Tuple, RelayTIMemoImpl>() {
        @Override
        public RelayTIMemoImpl apply(final Tuple tuple) {
            return new RelayTIMemoImpl();
        }};
        
    private Visitor2<Tuple, RelayTIMemoImpl> _ttlMemoRegister = new Visitor2<Tuple, RelayTIMemoImpl>() {
        @Override
        public void visit(final Tuple tuple, final RelayTIMemoImpl newMemo) throws Exception {
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
            
            _mbeanSupport.registerMBean(sb.toString(), newMemo.createMBean());
        }};
            
    private SimpleCache<Tuple, RelayTIMemoImpl> _ttlMemos  = 
            new SimpleCache<Tuple, RelayTIMemoImpl>(this._ttlMemoMaker, this._ttlMemoRegister);
    
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
            _mbeanSupport.registerMBean(sb.toString(), newMemo.createMBean());
        }};
        
    private final SimpleCache<Tuple, RelayMemoImpl> _bizMemos = 
            new SimpleCache<Tuple, RelayMemoImpl>(this._bizMemoMaker, this._bizMemoRegister);
    
    private RelayMemoImpl _level0Memo = new RelayMemoImpl()
        .fillTimeIntervalMemoWith(new Function<Enum<?>, TimeIntervalMemo>() {
            @Override
            public TimeIntervalMemo apply(final Enum<?> e) {
                return _ttlMemos.get(Tuple.of(e));
            }});
}
