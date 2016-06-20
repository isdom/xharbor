package org.jocean.xharbor.api;

import io.netty.handler.codec.http.HttpObject;
import rx.Observable.Transformer;

public interface HttpMessageTransformer extends Transformer<HttpObject, HttpObject> {

}
