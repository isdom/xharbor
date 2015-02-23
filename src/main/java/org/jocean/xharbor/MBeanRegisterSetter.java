/**
 * 
 */
package org.jocean.xharbor;

import org.jocean.j2se.MBeanRegister;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * @author isdom
 *
 */
public class MBeanRegisterSetter implements BeanPostProcessor {

    public MBeanRegisterSetter(final MBeanRegister register) {
        this._register = register;
    }
    
    @Override
    public Object postProcessBeforeInitialization(final Object bean, final String beanName)
            throws BeansException {
        if ( bean instanceof MBeanRegisterAware) {
            ((MBeanRegisterAware)bean).setMBeanRegister(this._register);
        }
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(final Object bean, final String beanName)
            throws BeansException {
        return bean;
    }

    private final MBeanRegister _register;
}
