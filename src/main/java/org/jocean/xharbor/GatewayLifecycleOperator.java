/**
 * 
 */
package org.jocean.xharbor;

import java.util.HashMap;
import java.util.Map;

import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.xharbor.api.RelayAgent;
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
    
    public GatewayLifecycleOperator(final RelayAgent relayAgent) {
        this._relayAgent = relayAgent;
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
            server.setRelayAgent(this._relayAgent);
            server.setAcceptIp(desc.getAcceptIp());
            server.setAcceptPort(desc.getAcceptPort());
            server.setLogByteStream(desc.isLogByteStream());
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
            server.setLogByteStream(desc.isLogByteStream());
            server.setIdleTimeSeconds(desc.getIdleTimeSeconds());
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
        private boolean logByteStream;
        private int idleTimeSeconds;
        
        @Override
        public String toString() {
            return "GatewayDesc [descrption=" + descrption + ", acceptPort="
                    + acceptPort + ", acceptIp=" + acceptIp
                    + ", logByteStream=" + logByteStream + ", idleTimeSeconds="
                    + idleTimeSeconds + "]";
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
    
    private final RelayAgent _relayAgent;
    private final Map<Integer, HttpGatewayServer> _servers = 
            new HashMap<Integer, HttpGatewayServer>();
}
