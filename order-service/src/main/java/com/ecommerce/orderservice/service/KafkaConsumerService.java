package com.ecommerce.orderservice.service;

import com.ecommerce.orderservice.config.KafkaTopicConfig;
import com.ecommerce.orderservice.event.OrderCreatedEvent;
import com.ecommerce.orderservice.util.LoggingUtils;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

/**
 * Baseline Kafka basics: manual-ack listener on the main consumer group, plus
 * a second listener on an independent consumer group reading the same
 * topics — demonstrating that every consumer group gets its own copy of
 * every message (docs/phase2_task10.md, "KafkaConsumerService").
 */
@Service
@Slf4j
public class KafkaConsumerService {

    @KafkaListener(
            topics = KafkaTopicConfig.ORDER_CREATED_TOPIC,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory")
    public void consumeOrderCreated(
            @Payload OrderCreatedEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            @Header(value = "correlationId", required = false) String correlationId,
            Acknowledgment ack) {

        if (correlationId != null) {
            MDC.put(LoggingUtils.CORRELATION_ID_KEY, correlationId);
        }
        try {
            log.info("Consuming ORDER_CREATED: orderId={}, partition={}, offset={}, thread={}",
                    event.getOrderId(), partition, offset, Thread.currentThread().getName());

            // Main business processing hook (notifications, downstream sync, etc.)
            log.info("ORDER_CREATED processed: orderId={}, orderNumber={}, total={}",
                    event.getOrderId(), event.getOrderNumber(), event.getTotalAmount());

            ack.acknowledge(); // manual commit — only after successful processing
        } catch (Exception e) {
            log.error("Failed ORDER_CREATED: orderId={}", event.getOrderId(), e);
            // Don't ack -> message redelivered on next poll.
        } finally {
            MDC.clear();
        }
    }

    /** Independent consumer group — same messages, its own offsets. */
    @KafkaListener(
            topics = {KafkaTopicConfig.ORDER_CREATED_TOPIC, KafkaTopicConfig.ORDER_UPDATED_TOPIC},
            groupId = "analytics-group",
            containerFactory = "kafkaListenerContainerFactory")
    public void consumeForAnalytics(
            @Payload Object event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            Acknowledgment ack) {
        log.info("Analytics: topic={}, type={}", topic, event.getClass().getSimpleName());
        ack.acknowledge();
    }
}
