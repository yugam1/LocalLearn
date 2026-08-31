package com.ecommerce.orderservice.config.quartz;

import com.ecommerce.orderservice.scheduler.InventorySyncQuartzJob;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.boot.autoconfigure.quartz.SchedulerFactoryBeanCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Quartz portion of Task 9: a persistence-capable, cluster-aware alternative
 * to {@code @Scheduled} for jobs that need to survive a restart or run on
 * exactly one node in a cluster (see docs/phase2_task9.md, "Distributed
 * Scheduling Problem" and "When would you use Quartz over @Scheduled").
 * {@code spring-boot-starter-quartz} auto-configures the {@code Scheduler}
 * bean itself; this class only supplies the job/trigger definitions and
 * swaps in an autowiring job factory so {@link InventorySyncQuartzJob} can
 * use {@link com.ecommerce.orderservice.repository.ProductRepository}.
 */
@Configuration
public class QuartzConfig {

    private final ApplicationContext applicationContext;

    public QuartzConfig(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Bean
    public SchedulerFactoryBeanCustomizer schedulerFactoryBeanCustomizer() {
        return schedulerFactoryBean -> {
            AutowiringSpringBeanJobFactory jobFactory = new AutowiringSpringBeanJobFactory();
            jobFactory.setApplicationContext(applicationContext);
            schedulerFactoryBean.setJobFactory(jobFactory);
        };
    }

    @Bean
    public JobDetail inventorySyncJobDetail() {
        return JobBuilder.newJob(InventorySyncQuartzJob.class)
                .withIdentity("inventorySyncJob")
                .withDescription("Periodically logs total product stock across the catalog")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger inventorySyncTrigger(JobDetail inventorySyncJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(inventorySyncJobDetail)
                .withIdentity("inventorySyncTrigger")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInMinutes(10)
                        .repeatForever())
                .build();
    }
}
