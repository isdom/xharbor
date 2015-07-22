/**
 * 
 */
package org.jocean.xharbor.router;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;

import org.jocean.xharbor.api.Router;
import org.jocean.xharbor.api.RoutingInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class Request2RoutingInfo implements Router<HttpRequest, RoutingInfo> {
    private static final Logger LOG = LoggerFactory
            .getLogger(Request2RoutingInfo.class);

    private static class RoutingInfoImpl implements RoutingInfo {
        RoutingInfoImpl(final String method, final String path) {
            this._method = method;
            this._path = path;
        }
        
        @Override
        public String getMethod() {
            return this._method;
        }
        
        @Override
        public String getPath() {
            return this._path;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result
                    + ((_method == null) ? 0 : _method.hashCode());
            result = prime * result + ((_path == null) ? 0 : _path.hashCode());
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
            RoutingInfoImpl other = (RoutingInfoImpl) obj;
            if (_method == null) {
                if (other._method != null)
                    return false;
            } else if (!_method.equals(other._method))
                return false;
            if (_path == null) {
                if (other._path != null)
                    return false;
            } else if (!_path.equals(other._path))
                return false;
            return true;
        }

        @Override
        public String toString() {
            return "[" + _method + " " + _path + "]";
        }

        private final String _method;
        private final String _path;
    }
    
    @Override
    public RoutingInfo calculateRoute(final HttpRequest request, final Context routectx) {
        final QueryStringDecoder decoder = new QueryStringDecoder(request.getUri());

        final RoutingInfo info = new RoutingInfoImpl(request.getMethod().name(), decoder.path());
        routectx.setProperty("path", info.getPath());
        routectx.setProperty("routingInfo", info);
        if ( LOG.isDebugEnabled()) {
            LOG.debug("dispatch for {}", info);
        }
        return info;
    }
}
