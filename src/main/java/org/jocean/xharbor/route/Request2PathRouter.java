/**
 * 
 */
package org.jocean.xharbor.route;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;

import org.jocean.xharbor.spi.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class Request2PathRouter implements Router<HttpRequest, String> {
    private static final Logger LOG = LoggerFactory
            .getLogger(Request2PathRouter.class);

    @Override
    public String calculateRoute(final HttpRequest request, final Context routectx) {
        final QueryStringDecoder decoder = new QueryStringDecoder(request.getUri());

        final String path = decoder.path();
        routectx.setProperty("path", path);
        if ( LOG.isDebugEnabled()) {
            LOG.debug("dispatch for path:{}", path);
        }
        return path;
    }
}
