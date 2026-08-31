package com.ecommerce.orderservice.service;

import com.ecommerce.orderservice.config.KafkaTopicConfig;
import com.ecommerce.orderservice.event.OrderCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Demonstrates the retry → Dead Letter Topic pattern on its own consumer
 * group ("dlt-demo-group"), independent from the real business consumers on
 * {@code order.created} (docs/phase2_task11.md, "Dead Letter Topic
 * Pattern"). Spring Kafka auto-creates {@code order.created-retry-0/1/2} and
 * {@code order.created.dlt} for this listener. The listener simulates a
 * transient failure on a fraction of messages purely to exercise the
 * retry/backoff/DLT path in a learning environment — it does not affect the
 * "real" consumers, which run in separate consumer groups.
 */
@Service
@Slf4j
public class DeadLetterTopicService {

    private static final double SIMULATED_FAILURE_RATE = 0.5;

    @RetryableTopic(
            attempts = "4", // 1 original + 3 retries
            backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 10000),
            dltTopicSuffix = ".dlt",
            include = {RuntimeException.class},
            exclude = {IllegalArgumentException.class},
            listenerContainerFactory = "retryableKafkaListenerContainerFactory")
    @KafkaListener(
            topics = KafkaTopicConfig.ORDER_CREATED_TOPIC,
            groupId = "dlt-demo-group",
            containerFactory = "retryableKafkaListenerContainerFactory")
    public void process(@Payload OrderCreatedEvent event) {
        log.info("DLT-demo processing attempt: orderId={}", event.getOrderId());
        if (ThreadLocalRandom.current().nextDouble() < SIMULATED_FAILURE_RATE) {
            throw new IllegalStateException("Simulated transient failure for orderId=" + event.getOrderId());
        }
        log.info("DLT-demo processed successfully: orderId={}", event.getOrderId());
    }

    @DltHandler
    public void handleDlt(
            @Payload OrderCreatedEvent event,
            @Header(KafkaHeaders.EXCEPTION_MESSAGE) String errorMessage,
            @Header(KafkaHeaders.ORIGINAL_TOPIC) String originalTopic) {
        log.error("DLT: orderId={}, originalTopic={}, error={}",
                event.getOrderId(), originalTopic, errorMessage);
        // Production: persist to an incident table and page on-call.
    }
}
