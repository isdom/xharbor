/**
 * 
 */
package org.jocean.xharbor.unused;

import java.util.HashMap;
import java.util.Map;

import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.xharbor.HttpGatewayServer;
import org.jocean.xharbor.util.ZKUpdater.Operator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.annotation.JSONField;

/**
 * @author isdom
 *
 */
public class GatewayLifecycleOperator implements Operator<Object> {

    private static final Logger LOG = LoggerFactory
            .getLogger(GatewayLifecycleOperator.class);
    
    public GatewayLifecycleOperator(final BusinessRepository businessRepository) {
//        this._relayAgent = relayAgent;
        this._businessRepository = businessRepository;
    }
    
    @Override
    public Object createContext() {
        return null;
    }

    @Override
    public Object doAddOrUpdate(
            final Object ctx, 
            final String root, 
            final TreeCacheEvent event)
            throws Exception {
        final ChildData data = event.getData();
        final int port = parseFromPath(root, data.getPath());
        if ( port > 0) {
            final GatewayDesc desc = parseFromData(data.getPath(), port, data.getData());
            if (null != desc ) {
                if ( LOG.isDebugEnabled()) {
                    LOG.debug("add or update gateway with {}", desc);
                }
                addOrUpdateGateway(desc);
            }
        }
        return ctx;
    }

    @Override
    public Object doRemove(
            final Object ctx, 
            final String root, 
            final TreeCacheEvent event)
            throws Exception {
        final ChildData data = event.getData();
        final int port = parseFromPath(root, data.getPath());
        if (port > 0) {
            if ( LOG.isDebugEnabled()) {
                LOG.debug("remove gateway with {}", port);
            }
            removeGateway(port);
        }
        return ctx;
    }

    @Override
    public Object applyContext(final Object ctx) {
        return ctx;
    }

    private void addOrUpdateGateway(final GatewayDesc desc) {
        HttpGatewayServer server = this._servers.get(desc.getAcceptPort());
        if (null == server) {
            server = new HttpGatewayServer();
            server.setRelayAgent(this._businessRepository.getBusinessAgent(desc.getRelayConfig()));
            server.setAcceptIp(desc.getAcceptIp());
            server.setAcceptPort(desc.getAcceptPort());
            server.setLogByteStream(desc.isLogByteStream());
            server.setCompressContent(desc.isCompressContent());
            server.setIdleTimeSeconds(desc.getIdleTimeSeconds());
            try {
                server.start();
                LOG.info("HttpGatewayServer {} started.", desc);
            } catch (Exception e) {
                LOG.warn("exception when invoke start for gateway desc {}, detail: {}",
                        desc, ExceptionUtils.exception2detail(e));
            }
            this._servers.put(desc.getAcceptPort(), server);
        }
        else {
            server.setRelayAgent(this._businessRepository.getBusinessAgent(desc.getRelayConfig()));
            server.setLogByteStream(desc.isLogByteStream());
            server.setIdleTimeSeconds(desc.getIdleTimeSeconds());
            server.setCompressContent(desc.isCompressContent());
            LOG.info("HttpGatewayServer {} updated.", desc);
        }
    }

    private void removeGateway(final int port) {
        HttpGatewayServer server = this._servers.remove(port);
        if (null != server) {
            server.stop();
            LOG.info("HttpGatewayServer {} stoped.", port);
        }
        else {
            LOG.warn("HttpGatewayServer {} not exist, MAYBE already stoped, so ignore stop call.", port);
        }
    }

    public static class GatewayDesc {
        
        private String descrption;
        private int acceptPort;
        private String acceptIp;
        private boolean logByteStream = false;
        private boolean compressContent = true;
        private int idleTimeSeconds = 200;
        private String relayConfig;
        
        @Override
        public String toString() {
            return "GatewayDesc [descrption=" + descrption + ", acceptPort="
                    + acceptPort + ", acceptIp=" + acceptIp + ", relayConfig="
                    + relayConfig + ", logByteStream=" + logByteStream
                    + ", compressContent=" + compressContent
                    + ", idleTimeSeconds=" + idleTimeSeconds + "]";
        }

        @JSONField(name="descrption")
        public String getDescrption() {
            return descrption;
        }

        @JSONField(name="descrption")
        public void setDescrption(final String descrption) {
            this.descrption = descrption;
        }

        public int getAcceptPort() {
            return acceptPort;
        }

        public void setAcceptPort(final int acceptPort) {
            this.acceptPort = acceptPort;
        }

        public String getAcceptIp() {
            return acceptIp;
        }

        public void setAcceptIp(final String acceptIp) {
            this.acceptIp = acceptIp;
        }

        @JSONField(name="logByteStream")
        public boolean isLogByteStream() {
            return logByteStream;
        }

        @JSONField(name="logByteStream")
        public void setLogByteStream(final boolean logByteStream) {
            this.logByteStream = logByteStream;
        }

        @JSONField(name="idleTimeSeconds")
        public int getIdleTimeSeconds() {
            return idleTimeSeconds;
        }

        @JSONField(name="idleTimeSeconds")
        public void setIdleTimeSeconds(final int idleTimeSeconds) {
            this.idleTimeSeconds = idleTimeSeconds;
        }
        
        @JSONField(name="compressContent")
        public boolean isCompressContent() {
            return compressContent;
        }

        @JSONField(name="compressContent")
        public void setCompressContent(final boolean compressContent) {
            this.compressContent = compressContent;
        }

        @JSONField(name="relayConfig")
        public String getRelayConfig() {
            return relayConfig;
        }

        @JSONField(name="relayConfig")
        public void setRelayConfig(String config) {
            this.relayConfig = config;
        }
    }
    
    private int parseFromPath(final String root, final String path) {
        if (path.length() <= root.length() ) {
            return 0;
        }
        final String pathToPort = path.substring(root.length() + ( !root.endsWith("/") ? 1 : 0 ));
        try {
            return Integer.parseInt(pathToPort);
        }
        catch (Exception e) {
            LOG.warn("exception when parse from path {}, detail:{}", 
                    path, ExceptionUtils.exception2detail(e));
        }
        return 0;
    }

    private GatewayDesc parseFromData(final String path, final int port, final byte[] data) {
        if (null == data || data.length == 0) {
            return null;
        }
        try {
            final GatewayDesc desc = JSON.parseObject(new String(data,  "UTF-8"), GatewayDesc.class);
            desc.setAcceptPort(port);
            desc.setAcceptIp("0.0.0.0");
            return desc;
        }
        catch (Exception e) {
            LOG.warn("exception when parse from path {}, detail:{}", 
                    path, ExceptionUtils.exception2detail(e));
        }
        return null;
    }
    
//    private final RelayAgent _relayAgent;
    private final BusinessRepository _businessRepository;
    private final Map<Integer, HttpGatewayServer> _servers = 
            new HashMap<Integer, HttpGatewayServer>();
}
