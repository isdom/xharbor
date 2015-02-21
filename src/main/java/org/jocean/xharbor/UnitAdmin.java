package org.jocean.xharbor;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.io.FilenameUtils;
import org.jocean.ext.unit.PropertyConfigurerFactory;
import org.jocean.ext.unit.ValueAwarePlaceholderConfigurer;
import org.jocean.ext.util.PackageUtils;
import org.jocean.ext.util.ant.SelectorUtils;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.j2se.MBeanRegisterSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.PropertyPlaceholderConfigurer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.util.ReflectionUtils;

public class UnitAdmin implements UnitAdminMXBean, ApplicationContextAware {

    private final static String[] _DEFAULT_SOURCE_PATTERNS = new String[]{"**/flow/**.xml"};

    public static interface UnitMXBean {

        public String getName();

        public String getSource();

        public String[] getParameters();

        public String[] getPlaceholders();

        public String getCreateTimestamp();

        public void close();
    }

    public static interface SourceMXBean {

        public String[] getPlaceholders();

    }

    private static final class StopInitCtxException extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(UnitAdmin.class);

    public UnitAdmin() {
        this._sourcePatterns = _DEFAULT_SOURCE_PATTERNS;
        this._sourcesRegister = new MBeanRegisterSupport("org.jocean:type=unitSource", null);
        this._unitsRegister = new MBeanRegisterSupport("org.jocean:type=units", null);
    }

    @Override
    public void setApplicationContext(final ApplicationContext applicationContext)
            throws BeansException {
        this._rootApplicationContext = applicationContext;
        //设置工程全局配置文件
        final PropertyPlaceholderConfigurer rootConfigurer = 
                null != this._rootApplicationContext 
                ? this._rootApplicationContext.getBean(PropertyPlaceholderConfigurer.class) 
                : null;
        if (rootConfigurer != null) {
            Field field = ReflectionUtils.findField(rootConfigurer.getClass(), "locations");
            field.setAccessible(true);
            this._rootPropertyFiles = (Resource[]) ReflectionUtils.getField(field, rootConfigurer);
        } else {
            this._rootPropertyFiles = null;
        }
    }

    public void init() {
        refreshSources();
    }

    private void refreshSources() {
        //this._unitsRegister.destroy();
        final Map<String, String[]> infos = getSourceInfo(this._sourcePatterns);
        for (Map.Entry<String, String[]> entry : infos.entrySet()) {
            final String[] placeholders = entry.getValue();
            final String suffix = "name=" + entry.getKey();
            if (!this._sourcesRegister.isRegistered(suffix)) {
                this._sourcesRegister.registerMBean(suffix, new SourceMXBean() {

                    @Override
                    public String[] getPlaceholders() {
                        return placeholders;
                    }
                });
            }
        }
    }

    public String[] getSourcePatterns() {
        return this._sourcePatterns;
    }

    public void setSourcePatterns(final String[] sourcePatterns) {
        this._sourcePatterns = sourcePatterns;
    }

    @Override
    public void newUnit(final String name, final String pattern, final String[] params)
            throws Exception {
        newUnit(name, pattern, new HashMap<String, String>() {
            private static final long serialVersionUID = 1L;
            {
                //  确保偶数
                for (int idx = 0; idx < (params.length / 2) * 2; idx += 2) {
                    this.put(params[idx], params[idx + 1]);
                }
            }
        });
    }

    @Override
    public void newUnit(final String name, final String pattern, final Map<String, String> params)
            throws Exception {
        createUnit(name, pattern, params, false);
    }

    public UnitMXBean createUnit(
            final String name,
            final String pattern,
            final Map<String, String> params,
            final boolean usingFirstWhenMatchedMultiSource) throws Exception {

        final int index = this._logidx.incrementAndGet();
        final String now = new Date().toString();

        final String[] sources = searchUnitSourceOf(new String[]{pattern});

        if (null == sources) {
            LOG.warn("can't found unit source matched {}, newUnit {} failed", pattern, name);
            addLog(Integer.toString(index), now + ": newUnit(" + name
                    + ") failed for can't found source matched ("
                    + pattern + ")");
            return null;
        } else if (sources.length > 1) {
            if (usingFirstWhenMatchedMultiSource) {
                LOG.warn("found unit source more than one matched {}, using first one to create unit {}",
                        pattern, name);
            } else {
                LOG.warn("found unit source more than one matched {}, failed to create unit {}/{}", pattern, name);
                addLog(Integer.toString(index), now + ": newUnit(" + name
                        + ") failed for found unit source > 1 "
                        + pattern);
                return null;
            }
        }

        final String objectNameSuffix = genUnitSuffix(name);
        final Object mock = newMockUnitMXBean(name);
        if (!reserveRegistration(objectNameSuffix, mock)) {
            addLog(Integer.toString(index), now + ": newUnit("
                    + name
                    + ") failed for can't reserve "
                    + objectNameSuffix);
            throw new RuntimeException("can't reserve " + objectNameSuffix + ", may be already registered");
        }

        try {
            final ValueAwarePlaceholderConfigurer configurer =
                    new ValueAwarePlaceholderConfigurer() {
                        {
                            this.setProperties(new Properties() {
                                private static final long serialVersionUID = 1L;

                                {
                                    this.putAll(params);
                                }
                            });

                        }
                    };

            if (this._rootPropertyFiles != null) {
                configurer.setLocations(this._rootPropertyFiles);
                configurer.setLocalOverride(true);//params(功能单元中的配置)覆盖全局配置
            }

            final String parentPath = FilenameUtils.getPathNoEndSeparator(name);
            final AbstractApplicationContext parentCtx = this._units.get(parentPath);
            
            if (LOG.isDebugEnabled()) {
                if (null != parentCtx) {
                    LOG.debug("found parent ctx {} for path {} ", parentCtx, parentPath);
                }
                else {
                    LOG.debug("can not found parent ctx for path {} ", parentPath);
                }
            }
            
            final AbstractApplicationContext ctx =
                    createConfigurableApplicationContext(
                            null != parentCtx ? parentCtx : this._rootApplicationContext,
                            sources[0], configurer);

            final UnitMXBean unit =
                    newUnitMXBean(
                            name,
                            sources[0],
                            now,
                            map2StringArray(params),
                            configurer.getTextedResolvedPlaceholdersAsStringArray());

            this._units.put(name, ctx);

            this._unitsRegister.replaceRegisteredMBean(objectNameSuffix, mock, unit);

            addLog(Integer.toString(index), now + ": newUnit(" + name + ") succeed.");

            return unit;
        } catch (Exception e) {
            this._unitsRegister.unregisterMBean(objectNameSuffix);
            addLog(Integer.toString(index), now + ": newUnit(" + name + ") failed for "
                    + ExceptionUtils.exception2detail(e));
            throw e;
        }
    }


    /**
     * @param params
     * @return
     */
    private String[] map2StringArray(final Map<String, String> params) {
        return new ArrayList<String>() {
            private static final long serialVersionUID = 1L;
        {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                this.add(entry.getKey() + "<--" + entry.getValue());
            }
        }}.toArray(new String[0]);
    }

    /**
     * @param name
     * @param source
     * @param now
     * @param params
     * @param placeholders
     * @return
     */
    private UnitMXBean newUnitMXBean(
            final String name,
            final String source,
            final String now,
            final String[] params,
            final String[] placeholders) {
        return new UnitMXBean() {

            @Override
            public String getSource() {
                return source;
            }

            @Override
            public String[] getParameters() {
                return params;
            }

            @Override
            public String[] getPlaceholders() {
                return placeholders;
            }

            @Override
            public String getCreateTimestamp() {
                return now;
            }

            @Override
            public void close() {
                deleteUnit(name);
            }

            @Override
            public String getName() {
                return name;
            }
        };
    }

    private String genUnitSuffix(final String name) {
        return "name=" + name;
    }

    private boolean reserveRegistration(final String objectNameSuffix, final Object mock) {
        return this._unitsRegister.registerMBean(objectNameSuffix, mock);
    }

    private Object newMockUnitMXBean(final String name) {
        return new UnitMXBean() {

            @Override
            public String getSource() {
                return null;
            }

            @Override
            public String[] getParameters() {
                return null;
            }

            @Override
            public String[] getPlaceholders() {
                return null;
            }

            @Override
            public String getCreateTimestamp() {
                return null;
            }

            @Override
            public void close() {
            }

            @Override
            public String getName() {
                return name;
            }
        };
    }

    @Override
    public void deleteUnit(final String name) {
        final int index = this._logidx.incrementAndGet();
            this._unitsRegister.unregisterMBean(genUnitSuffix(name));
            final AbstractApplicationContext ctx = this._units.remove(name);
            if (null != ctx) {
                ctx.close();
                addLog(Integer.toString(index),
                        new Date().toString() + ": deleteUnit(name=" + name
                                + ") succeed.)");
            } else {
                LOG.warn("unit ({})'s AbstractApplicationContext is null, unit maybe deleted already", name);
                addLog(Integer.toString(index),
                        new Date().toString() + ": deleteUnit(name=" + name
                                + ") failed for AbstractApplicationContext is null.)");
            }
    }

    @Override
    public void deleteAllUnit() {
        //  remove and close all unit
        while (!this._units.isEmpty()) {
            deleteUnit(this._units.keySet().iterator().next());
        }
    }

    @Override
    public Map<String, String[]> getSourceInfo(final String sourcePattern) {
        return getSourceInfo(new String[]{sourcePattern});
    }

    public Map<String, String[]> getSourceInfo(final String[] sourcePatterns) {
        final String[] sources = searchUnitSourceOf(sourcePatterns);

        final Map<String, String[]> infos = new HashMap<>();

        if (null == sources) {
            LOG.warn("can't found unit source matched {}, getSourcesInfo failed", Arrays.toString(sourcePatterns));
            return infos;
        }

        for (String source : sources) {
            final String unitSource = source;
            final ValueAwarePlaceholderConfigurer configurer =
                    new ValueAwarePlaceholderConfigurer() {
                        {
                            this.setIgnoreUnresolvablePlaceholders(true);
                        }

                        @Override
                        protected void processProperties(ConfigurableListableBeanFactory beanFactoryToProcess, Properties props)
                                throws BeansException {
                            super.processProperties(beanFactoryToProcess, props);
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("after processProperties for source {}", unitSource);
                            }
                            throw new StopInitCtxException();
                        }
                    };

            try {
                createConfigurableApplicationContext(null, source, configurer);
            } catch (StopInitCtxException ignored) {
            }
            infos.put(unitSource, configurer.getTextedResolvedPlaceholdersAsStringArray());
        }

        return infos;
    }

    @Override
    public String[] getLogs() {
        return this._logs.toArray(new String[this._logs.size()]);
    }

    @Override
    public void resetLogs() {
        this._logs.clear();
    }

    private void addLog(final String id, final String msg) {
        this._logs.add(id + ":" + msg + "\r\n");
    }

    /**
     * @param unitSource
     * @param configurer
     * @return
     */
    private AbstractApplicationContext createConfigurableApplicationContext(
            final ApplicationContext parentCtx,
            final String unitSource,
            final PropertyPlaceholderConfigurer configurer) {
        final AbstractApplicationContext topCtx =
                new ClassPathXmlApplicationContext(
                        new String[]{"org/jocean/ext/ebus/spring/unitParent.xml"}, 
                        parentCtx);

        final PropertyConfigurerFactory factory =
                topCtx.getBean(PropertyConfigurerFactory.class);

        factory.setConfigurer(configurer);

        final AbstractApplicationContext ctx =
                new ClassPathXmlApplicationContext(
                        new String[]{
                                "org/jocean/ext/ebus/spring/Configurable.xml",
                                unitSource},
                        topCtx);
        return ctx;
    }

    private String[] searchUnitSourceOf(final String[] sourcePatterns) {

        if (LOG.isDebugEnabled()) {
            LOG.debug("try to match pattern: {}", Arrays.toString(sourcePatterns));
        }
        final List<String> sources = new ArrayList<>();
        try {
            final Map<URL, String[]> allRes = PackageUtils.getAllCPResourceAsPathlike();
            for (Map.Entry<URL, String[]> entry : allRes.entrySet()) {
                for (String res : entry.getValue()) {
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("found resource: {}", res);
                    }
                    for (String pattern : sourcePatterns) {
                        if (SelectorUtils.match(pattern, res)) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("found matched unit source: {}", res);
                            }
                            sources.add(res);
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOG.error("exception when search pattern {}, detail : {}", Arrays.toString(sourcePatterns),
                    ExceptionUtils.exception2detail(e));
        }

        return sources.isEmpty() ? null : sources.toArray(new String[sources.size()]);
    }

    private String[] _sourcePatterns;

    private ApplicationContext _rootApplicationContext = null;

    private Resource[] _rootPropertyFiles = null;

    private final MBeanRegisterSupport _sourcesRegister;

    private final MBeanRegisterSupport _unitsRegister;

    private final Map<String, AbstractApplicationContext> _units = new ConcurrentHashMap<>();

    private final AtomicInteger _logidx = new AtomicInteger(0);

    private final Queue<String> _logs = new ConcurrentLinkedQueue<>();
}
