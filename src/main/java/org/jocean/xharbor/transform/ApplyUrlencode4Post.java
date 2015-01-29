/**
 * 
 */
package org.jocean.xharbor.transform;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.io.InputStream;
import java.net.URLEncoder;

import org.apache.commons.io.IOUtils;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Triple;
import org.jocean.xharbor.spi.HttpRequestTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class ApplyUrlencode4Post implements Cloneable, HttpRequestTransformer, HttpRequestTransformer.Builder {
    
    private static final Logger LOG = LoggerFactory
            .getLogger(ApplyUrlencode4Post.class);
    
    private static boolean isXWWWFormURLEncodedType(final HttpHeaders headers) {
        final String contentType = headers.get(HttpHeaders.Names.CONTENT_TYPE);
        return null != contentType 
            ? contentType.startsWith(HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED) 
            : false;
    }

    @Override
    protected ApplyUrlencode4Post clone() throws CloneNotSupportedException {
        return new ApplyUrlencode4Post(
                this._path, this._noapplyFeature, this._applyKeys);
    }
    
    public ApplyUrlencode4Post(
            final String path, 
            final String noapplyFeature,
            final String[] applyKeys) {
        this._path = path;
        this._noapplyFeature = noapplyFeature;
        this._applyKeys = applyKeys;
    }
    
    public String path() {
        return this._path;
    }
    
    @Override
    public HttpRequestTransformer build(final HttpRequest httpRequest) {
        if (!httpRequest.getMethod().equals(HttpMethod.POST)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("request {} is not POST, not transform.", httpRequest);
            }
            return null;
        }
        
        if (!isXWWWFormURLEncodedType(httpRequest.headers())) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("request {} is not {} mimeType, not transform.", 
                        httpRequest, HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED);
            }
            return null;
        }
        
        if ( !isMatchPath(httpRequest, this._path) ) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("request {} is not match path({}), not transform.", httpRequest, this._path);
            }
            return null;
        }
        
        if (LOG.isDebugEnabled()) {
            LOG.debug("request {} MAYBE need transform.", httpRequest);
        }
        return this;
    }
    
    @Override
    public FullHttpRequest transform(final HttpRequest httpRequest, final ByteBuf content) {
        if (!httpRequest.getMethod().equals(HttpMethod.POST)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("request {} is not POST, not transform.", httpRequest);
            }
            return null;
        }
        
        if (!isXWWWFormURLEncodedType(httpRequest.headers())) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("request {} is not {} mimeType, not transform.", httpRequest, HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED);
            }
            return null;
        }
        
        if ( !isMatchPath(httpRequest, this._path) ) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("request {} is not match path({}), not transform.", httpRequest, this._path);
            }
            return null;
        }
        
        final InputStream is = new ByteBufInputStream(content.slice());
        
        try {
            String msg = IOUtils.toString(is, "UTF-8");
            if ( -1 != msg.indexOf(this._noapplyFeature) ) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("request {} contains noapply feature ({}), not transform.", httpRequest, this._noapplyFeature);
                }
                return null;
            }
            
            // try transform
            if (LOG.isDebugEnabled()) {
                LOG.debug("request {}'s original content:({})", 
                        httpRequest.getUri(), msg);
            }
            
            final StringBuilder sb = new StringBuilder();
            for ( String key : this._applyKeys) {
                final Triple<String,String,String> splitted = splitByKey(msg, key);
                final String tobeUrlencoded = splitted.getFirst();
                if (null != tobeUrlencoded) {
                    sb.append(URLEncoder.encode(tobeUrlencoded, "UTF-8"));
                }
                if (null != splitted.getSecond()) {
                    sb.append(splitted.getSecond());
                }
                msg = splitted.getThird();
                if (null == msg) {
                    break;
                }
            }
            if (null!=msg) {
                sb.append(URLEncoder.encode(msg, "UTF-8"));
            }
            final String encodedMsg = sb.toString();
            final ByteBuf newContent = Unpooled.wrappedBuffer(encodedMsg.getBytes("UTF-8"));
            final DefaultFullHttpRequest newRequest = new DefaultFullHttpRequest(
                    httpRequest.getProtocolVersion(), 
                    httpRequest.getMethod(), 
                    httpRequest.getUri(),
                    newContent
                    );
            newRequest.headers().add(httpRequest.headers());
            HttpHeaders.setContentLength(newRequest, newContent.readableBytes());
            //  TODO need remove transfer-encoding: chunked http header?
            
            if (LOG.isDebugEnabled()) {
                LOG.debug("request {}'s transformed content:({})", 
                        httpRequest.getUri(), encodedMsg);
            }
            
            return newRequest;
        } catch (Exception e) {
            LOG.warn("exception when IOUtils.toString, detail:{}", 
                    ExceptionUtils.exception2detail(e));
        } finally {
            IOUtils.closeQuietly(is);
        }
        
        return null;
    }

    private static boolean isMatchPath(final HttpRequest httpRequest, final String path) {
        return new QueryStringDecoder(httpRequest.getUri()).path().equals(path);
    }

    /**
     * @param msg
     * @param key
     */
    private Triple<String,String,String> splitByKey(final String msg, final String key) {
        final int p = msg.indexOf(key);
        if (-1!=p) {
            return Triple.of(
                    p>0 ? msg.substring(0, p) : null, 
                    key,
                    p + key.length() < msg.length() ? msg.substring(p + key.length()) : null);
        }
        else {
            return Triple.of(null,null,msg);
        }
    }

    private final String _path;
    private final String _noapplyFeature;
    private final String[] _applyKeys;
}
