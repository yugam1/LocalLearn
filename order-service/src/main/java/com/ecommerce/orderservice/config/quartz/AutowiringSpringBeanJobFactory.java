package com.ecommerce.orderservice.config.quartz;

import org.quartz.spi.TriggerFiredBundle;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.scheduling.quartz.SpringBeanJobFactory;

/**
 * Quartz instantiates {@link org.quartz.Job} classes itself via reflection —
 * they are never registered as Spring beans, so plain {@code @Autowired}
 * fields on a {@code Job} are normally left {@code null}. This factory
 * autowires each freshly-created job instance against the Spring context
 * before Quartz calls {@code execute(...)}. Registered via
 * {@link QuartzConfig#schedulerFactoryBeanCustomizer()}. See
 * docs/phase2_task9.md, "Quartz — enterprise scheduling with persistence".
 */
public class AutowiringSpringBeanJobFactory extends SpringBeanJobFactory implements ApplicationContextAware {

    private transient AutowireCapableBeanFactory beanFactory;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.beanFactory = applicationContext.getAutowireCapableBeanFactory();
    }

    @Override
    protected Object createJobInstance(TriggerFiredBundle bundle) throws Exception {
        Object job = super.createJobInstance(bundle);
        beanFactory.autowireBean(job);
        return job;
    }
}
