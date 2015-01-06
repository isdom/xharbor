package org.jocean.xharbor.spi;

import org.jocean.idiom.Propertyable;

public interface Router<INPUT, OUTPUT> {
    public interface Context extends Propertyable<Context> {
    }
    
    public OUTPUT calculateRoute(final INPUT input, final Context context);
}
