package com.ecommerce.orderservice.config;

import com.ecommerce.orderservice.kafka.partitioner.OrderPartitioner;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Map;

/**
 * Producer settings (acks, retries, idempotence, batching, compression) come
 * from {@code spring.kafka.producer.*} in application.yml — this class only
 * adds the one setting Spring Boot's auto-configuration can't express: the
 * custom {@link OrderPartitioner}. See docs/phase2_task11.md, "Custom
 * Partitioner" ("Register: configProps.put(PARTITIONER_CLASS_CONFIG, ...)").
 */
@Configuration
@RequiredArgsConstructor
public class KafkaProducerConfig {

    private final KafkaProperties kafkaProperties;

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> props = kafkaProperties.buildProducerProperties(null);
        props.put(ProducerConfig.PARTITIONER_CLASS_CONFIG, OrderPartitioner.class.getName());
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
