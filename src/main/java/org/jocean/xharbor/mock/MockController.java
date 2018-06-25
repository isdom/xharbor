package org.jocean.xharbor.mock;

import java.util.concurrent.TimeUnit;

import javax.ws.rs.GET;
import javax.ws.rs.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Controller;

import rx.Observable;

@Controller
@Scope("singleton")
public class MockController {

    @Path("/mock")
    @GET
    public Observable<String> mock() {
        return Observable.just("mock response").delay(this._timeout, TimeUnit.MILLISECONDS);
    }

    @Value("${timeout}")
    public void setTimeout(final int timeout) {
        this._timeout = timeout;
    }

    private long _timeout = 1500;
}
