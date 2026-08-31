package com.ecommerce.orderservice.scheduler;

import com.ecommerce.orderservice.model.Product;
import com.ecommerce.orderservice.repository.ProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * Quartz job that periodically snapshots total stock across the catalog.
 * Unlike {@code @Scheduled} beans, Quartz owns instantiation of this class
 * (a new instance per fire), so dependencies are injected post-construction
 * by {@link com.ecommerce.orderservice.config.quartz.AutowiringSpringBeanJobFactory}
 * rather than via a constructor — hence the field injection and required
 * no-arg constructor. See docs/phase2_task9.md, "Quartz" and
 * {@link com.ecommerce.orderservice.config.quartz.QuartzConfig}.
 */
@Slf4j
public class InventorySyncQuartzJob implements Job {

    @Autowired
    private ProductRepository productRepository;

    @Override
    public void execute(JobExecutionContext context) {
        try {
            List<Product> products = productRepository.findAll();
            int totalStock = products.stream().mapToInt(Product::getStock).sum();
            log.info("[QUARTZ] inventory sync: products={}, totalStock={}, thread={}",
                    products.size(), totalStock, Thread.currentThread().getName());
        } catch (Exception e) {
            log.error("[QUARTZ] inventory sync job failed", e);
        }
    }
}
