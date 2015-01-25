/**
 * 
 */
package org.jocean.xharbor.route;

import java.net.URI;

import org.jocean.idiom.Function;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.SimpleCache;
import org.jocean.idiom.Tuple;
import org.jocean.idiom.Visitor2;
import org.jocean.j2se.MBeanRegisterSupport;
import org.jocean.xharbor.relay.RelayContext;
import org.jocean.xharbor.relay.RelayContext.RESULT;
import org.jocean.xharbor.relay.RelayContext.RelayMemo;
import org.jocean.xharbor.relay.RelayContext.STEP;
import org.jocean.xharbor.spi.Router;
import org.jocean.xharbor.util.BizMemoImpl;
import org.jocean.xharbor.util.TIMemoImplOfRanges;
import org.jocean.xharbor.util.TimeIntervalMemo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Range;

/**
 * @author isdom
 */
public class Target2RelayCtx implements Router<Target, RelayContext> {

    @SuppressWarnings("unused")
    private static final Logger LOG = LoggerFactory
            .getLogger(Target2RelayCtx.class);
    
    public interface RelayMemoBuilder {
        public RelayContext.RelayMemo build(final Target target, final RoutingInfo info);
    }
    
    public Target2RelayCtx(final RelayMemoBuilder builder) {
        this._builder = builder;
        _mbeanSupport.registerMBean("name=relays", this._level0Memo.createMBean());
    }
    
    private static final String[] _OBJNAME_KEYS = new String[]{"path", "method", "dest"};

    private static final String normalizeString(final String input) {
        return input.replaceAll(":", "-");
    }
    
    private static final String uri2value(final URI uri) {
        return normalizeString(uri.toString());
    }
    
    @Override
    public RelayContext calculateRoute(final Target target, final Context routectx) {
        final RoutingInfo info = routectx.getProperty("routingInfo");
        
        final RelayContext.RelayMemo memoBase = 
                InterfaceUtils.combineImpls(RelayContext.RelayMemo.class, 
                this._level0Memo,
                this._bizMemos.get(Tuple.of(normalizeString(info.getPath()))),
                this._bizMemos.get(Tuple.of(normalizeString(info.getPath()), info.getMethod()))
                );
        final RelayContext.RelayMemo memo = 
                null != target 
                ? compositeRelayMemo(target, info, memoBase)
                : memoBase;
        
        return new RelayContext() {

            @Override
            public URI relayTo() {
                return null != target ? target.serviceUri() : null;
            }

            @Override
            public RelayMemo memo() {
                return memo;
            }};
    }

    /**
     * @param target
     * @param info
     * @param base
     * @return
     */
    private RelayMemo compositeRelayMemo(
            final Target target,
            final RoutingInfo info, 
            final RelayContext.RelayMemo base) {
        if ( null != this._builder) {
            final RelayMemo memo = this._builder.build(target, info);
            if ( null != memo ) {
                return InterfaceUtils.combineImpls(RelayContext.RelayMemo.class, 
                    base,
                    this._bizMemos.get(Tuple.of(
                            normalizeString(info.getPath()), info.getMethod(), uri2value(target.serviceUri()))),
                    memo
                    );
            }
        }
        // others
        return InterfaceUtils.combineImpls(RelayContext.RelayMemo.class, 
            base,
            this._bizMemos.get(Tuple.of(
                    normalizeString(info.getPath()), info.getMethod(), uri2value(target.serviceUri())))
            );
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
    
    private final RelayMemoBuilder _builder;
    
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
