package org.jocean.xharbor;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.util.ReflectionUtils;

public class UnitAdmin implements UnitAdminMXBean, ApplicationContextAware {

    private final static String[] _DEFAULT_SOURCE_PATTERNS = new String[]{"**/units/**.xml"};

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
    public boolean newUnit(
            final String unitName, 
            final String pattern, 
            final String[] unitParameters) {
        return newUnit(unitName, pattern, new HashMap<String, String>() {
            private static final long serialVersionUID = 1L;
            {
                //  确保偶数
                for (int idx = 0; idx < (unitParameters.length / 2) * 2; idx += 2) {
                    this.put(unitParameters[idx], unitParameters[idx + 1]);
                }
            }
        });
    }

    @Override
    public boolean newUnit(
            final String unitName, 
            final String pattern, 
            final Map<String, String> unitParameters) {
        return null != createUnit(unitName, pattern, unitParameters, false);
    }

    public UnitMXBean createUnit(
            final String unitName,
            final String pattern,
            final Map<String, String> unitParameters,
            final boolean usingFirstWhenMatchedMultiSource) {
        final String[] sources = searchUnitSourceOf(new String[]{pattern});

        if (null == sources) {
            LOG.warn("can't found unit source matched {}, newUnit {} failed", pattern, unitName);
            addLog(" newUnit(" + unitName
                    + ") failed for can't found source matched ("
                    + pattern + ")");
            return null;
        } else if (sources.length > 1) {
            if (!usingFirstWhenMatchedMultiSource) {
                LOG.warn("found unit source more than one matched {}, failed to create unit {}/{}", pattern, unitName);
                addLog(" newUnit(" + unitName
                        + ") failed for found unit source > 1 "
                        + pattern);
                return null;
            } else {
                LOG.warn("found unit source more than one matched {}, using first one to create unit {}",
                        pattern, unitName);
            }
        }

        return doCreateUnit(unitName, sources[0], unitParameters);
    }
    
    private UnitMXBean doCreateUnit(
            final String unitName,
            final String unitSource,
            final Map<String, String> unitParameters) {

        final String objectNameSuffix = genUnitSuffix(unitName);
        final Object mock = newMockUnitMXBean(unitName);
        if (!reserveRegistration(objectNameSuffix, mock)) {
            addLog(" newUnit("
                    + unitName
                    + ") failed for can't reserve "
                    + objectNameSuffix);
            throw new RuntimeException("can't reserve " + objectNameSuffix + ", may be already registered");
        }

        final ValueAwarePlaceholderConfigurer configurer =
                new ValueAwarePlaceholderConfigurer() {
                    {
                        this.setProperties(new Properties() {
                            private static final long serialVersionUID = 1L;

                            {
                                this.putAll(unitParameters);
                            }
                        });

                    }
                };

        if (this._rootPropertyFiles != null) {
            configurer.setLocations(this._rootPropertyFiles);
            configurer.setLocalOverride(true);//params(功能单元中的配置)覆盖全局配置
        }

        final Node parentNode = getParentNode(unitName);
        final ApplicationContext parentCtx = 
                null != parentNode 
                    ? parentNode.applicationContext() 
                    : this._rootApplicationContext;
        
        if (null == parentCtx) {
            registerUnactiveUnit(
                    unitName, 
                    unitSource, 
                    unitParameters,
                    objectNameSuffix, 
                    parentNode,
                    mock);
            addLog(" newUnit(" + unitName + ") failed for unactive parent.");
            return null;
        }
        try {
            final ConfigurableApplicationContext ctx =
                    createConfigurableApplicationContext(
                            parentCtx,
                            unitSource, 
                            configurer);

            if ( null != parentNode) {
                parentNode.addChild(unitName);
            }
            final Node node = new Node(ctx, unitName, unitSource, unitParameters);
            this._units.put(unitName, node);
            
            final UnitMXBean unit =
                    newUnitMXBean(
                            null!=ctx,
                            unitName,
                            unitSource,
                            new Date().toString(),
                            map2StringArray(unitParameters),
                            configurer.getTextedResolvedPlaceholdersAsStringArray(),
                            node.childrenUnits());

            this._unitsRegister.replaceRegisteredMBean(objectNameSuffix, mock, unit);

            addLog(" newUnit(" + unitName + ") succeed.");

            return unit;
        } catch (Exception e) {
            registerUnactiveUnit(
                    unitName, 
                    unitSource, 
                    unitParameters,
                    objectNameSuffix, 
                    parentNode,
                    mock);
            LOG.warn("exception when createUnit for {}, detail:{}", unitName, ExceptionUtils.exception2detail(e));
            addLog(" newUnit(" + unitName + ") failed for "
                    + ExceptionUtils.exception2detail(e));
            return null;
        }
    }

    /**
     * @param unitName
     * @return
     */
    private Node getParentNode(final String unitName) {
        final String parentPath = FilenameUtils.getPathNoEndSeparator(unitName);
        final Node parentNode = "".equals(parentPath) ? null : this._units.get(parentPath);
        if (LOG.isDebugEnabled()) {
            if (null != parentNode) {
                LOG.debug("found parent node {} for path {} ", parentNode, parentPath);
            }
            else {
                LOG.debug("can not found parent node for path {} ", parentPath);
            }
        }
        return parentNode;
    }

    /**
     * @param unitName
     * @param unitSource
     * @param unitParameters
     * @param objectNameSuffix
     * @param parentNode
     * @param mock 
     */
    private void registerUnactiveUnit(
            final String unitName,
            final String unitSource, 
            final Map<String, String> unitParameters,
            final String objectNameSuffix, 
            final Node parentNode, 
            final Object mock) {
        if ( null != parentNode) {
            parentNode.addChild(unitName);
        }
        final Node node = new Node(null, unitName, unitSource, unitParameters);
        this._units.put(unitName, node);
        
        final UnitMXBean unit =
                newUnitMXBean(
                        false,
                        unitName,
                        unitSource,
                        new Date().toString(),
                        map2StringArray(unitParameters),
                        null,
                        node.childrenUnits());

        this._unitsRegister.replaceRegisteredMBean(objectNameSuffix, mock, unit);
    }

    public UnitMXBean updateUnit(
            final String unitName,
            final Map<String, String> newUnitParameters) {
        if (null == this._units.get(unitName)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("can't found unit named {}, update failed.", unitName);
            }
            addLog(" can't found unit named "+ unitName + ", update failed.");
            return null;
        }
        final Node[] nodes = doDeleteUnit(unitName).toArray(new Node[0]);
        final UnitMXBean mbean = doCreateUnit(nodes[0]._unitName, nodes[0]._unitSource, newUnitParameters);
        for (int idx = 1; idx < nodes.length; idx++) {
            final Node node = nodes[idx];
            doCreateUnit(node._unitName, node._unitSource, node._unitParameters);
        }
        return mbean;
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

    private UnitMXBean newUnitMXBean(
            final boolean isActive,
            final String name,
            final String source,
            final String now,
            final String[] params,
            final String[] placeholders,
            final List<String> childrenUnits) {
        return new UnitMXBean() {

            @Override
            public boolean isActive() {
                return isActive;
            }
            
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

            @Override
            public String[] getChildrenUnits() {
                return childrenUnits.toArray(new String[0]);
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
            public boolean isActive() {
                return false;
            }
            
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

            @Override
            public String[] getChildrenUnits() {
                return null;
            }
        };
    }

    @Override
    public boolean deleteUnit(final String unitName) {
        return null != doDeleteUnit(unitName);
    }
    
    public List<Node> doDeleteUnit(final String unitName) {
        this._unitsRegister.unregisterMBean(genUnitSuffix(unitName));
        final Node node = this._units.remove(unitName);
        if (null != node) {
            final List<Node> nodesDeleted = new ArrayList<>();
            nodesDeleted.add(node);
            for (String child : node.childrenUnitsAsArray()) {
                final List<Node> nodes = doDeleteUnit(child);
                if (null != nodes) {
                    nodesDeleted.addAll(nodes);
                }
            }
            node.closeApplicationContext();
            final Node parentNode = getParentNode(unitName);
            if (null!=parentNode) {
                parentNode.removeChild(unitName);
            }
            addLog(" deleteUnit(name=" + unitName + ") success.)");
            return nodesDeleted;
        } else {
            LOG.warn("can't found unit named {}, maybe deleted already", unitName);
            addLog(" deleteUnit(name=" + unitName + ") failure.)");
            return null;
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

    private void addLog(final String msg) {
        this._logs.add(this._logidx.incrementAndGet() 
                + ":" + new Date().toString() 
                + ":" + msg + "\r\n");
    }

    /**
     * @param unitSource
     * @param configurer
     * @return
     */
    private ConfigurableApplicationContext createConfigurableApplicationContext(
            final ApplicationContext parentCtx,
            final String unitSource,
            final PropertyPlaceholderConfigurer configurer) {
        final ApplicationContext topCtx =
                new ClassPathXmlApplicationContext(
                    new String[]{"org/jocean/ext/ebus/spring/unitParent.xml"}, 
                    parentCtx);

        final PropertyConfigurerFactory factory =
                topCtx.getBean(PropertyConfigurerFactory.class);

        factory.setConfigurer(configurer);

        return new ClassPathXmlApplicationContext(
                    new String[]{"org/jocean/ext/ebus/spring/Configurable.xml", unitSource},
                    topCtx);
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

    private static class Node {
        Node(final ConfigurableApplicationContext applicationContext,
            final String unitName,
            final String unitSource, 
            final Map<String, String> unitParameters) {
            this._applicationContext = applicationContext;
            this._unitName = unitName;
            this._unitSource = unitSource;
            this._unitParameters = Collections.unmodifiableMap(unitParameters);
        }
        
        ApplicationContext applicationContext() {
            return this._applicationContext;
        }
        
        void closeApplicationContext() {
            if (null != this._applicationContext) {
                this._applicationContext.close();
            }
        }
        
        void addChild(final String child) {
            this._children.add(child);
        }
        
        void removeChild(final String child) {
            this._children.remove(child);
        }
        
        List<String> childrenUnits() {
            return this._children;
        }
        
        String[] childrenUnitsAsArray() {
            return this._children.toArray(new String[0]);
        }
        
        @Override
        public String toString() {
            return "Node [unitName=" + _unitName + ", applicationContext="
                    + _applicationContext + ", unitSource=" + _unitSource
                    + ", children's count=" + _children.size() + "]";
        }

        private final List<String> _children = new ArrayList<>();
        private final ConfigurableApplicationContext _applicationContext;
        private final String _unitName;
        private final String _unitSource;
        private final Map<String, String>   _unitParameters;
    }
    
    private String[] _sourcePatterns;

    private ApplicationContext _rootApplicationContext = null;

    private Resource[] _rootPropertyFiles = null;

    private final MBeanRegisterSupport _sourcesRegister;

    private final MBeanRegisterSupport _unitsRegister;

    private final Map<String, Node> _units = new ConcurrentHashMap<>();

    private final AtomicInteger _logidx = new AtomicInteger(0);

    private final Queue<String> _logs = new ConcurrentLinkedQueue<>();
}
