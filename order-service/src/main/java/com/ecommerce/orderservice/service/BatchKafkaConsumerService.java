package com.ecommerce.orderservice.service;

import com.ecommerce.orderservice.config.KafkaTopicConfig;
import com.ecommerce.orderservice.event.OrderCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * High-throughput batch consumption — one offset commit per batch instead of
 * per record. See docs/phase2_task11.md, "Batch Consumer". Uses the
 * dedicated {@code batchKafkaListenerContainerFactory}
 * (see {@link com.ecommerce.orderservice.config.KafkaConsumerConfig}), which
 * has {@code setBatchListener(true)}.
 */
@Service
@Slf4j
public class BatchKafkaConsumerService {

    @KafkaListener(
            topics = KafkaTopicConfig.ORDER_CREATED_TOPIC,
            groupId = "batch-group",
            containerFactory = "batchKafkaListenerContainerFactory")
    public void consumeBatch(
            List<OrderCreatedEvent> events,
            @Header(KafkaHeaders.OFFSET) List<Long> offsets,
            Acknowledgment ack) {

        if (events.isEmpty()) {
            ack.acknowledge();
            return;
        }

        log.info("Batch received: {} messages", events.size());
        long start = System.currentTimeMillis();

        events.forEach(event -> log.debug("Batch processing orderId={}", event.getOrderId()));

        long avgMs = (System.currentTimeMillis() - start) / events.size();
        log.info("Batch done: count={}, avgMs={}", events.size(), avgMs);

        ack.acknowledge(); // single commit for the entire batch
    }
}
