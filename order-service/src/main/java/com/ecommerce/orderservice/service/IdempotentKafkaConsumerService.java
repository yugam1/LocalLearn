package com.ecommerce.orderservice.service;

import com.ecommerce.orderservice.config.KafkaTopicConfig;
import com.ecommerce.orderservice.event.OrderCreatedEvent;
import com.ecommerce.orderservice.model.ProcessedEvent;
import com.ecommerce.orderservice.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * At-least-once delivery means a crash between "processed" and "acked" can
 * redeliver the same record. This listener checks {@link ProcessedEvent}
 * before doing any work and skips duplicates. See docs/phase2_task11.md,
 * "Idempotent Consumer".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotentKafkaConsumerService {

    private final ProcessedEventRepository processedEventRepository;

    @KafkaListener(
            topics = KafkaTopicConfig.ORDER_CREATED_TOPIC,
            groupId = "idempotent-consumer-group",
            containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void consumeIdempotent(
            @Payload OrderCreatedEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment ack) {

        if (processedEventRepository.existsByEventId(event.getEventId())) {
            log.warn("DUPLICATE: eventId={} — skipping", event.getEventId());
            ack.acknowledge();
            return;
        }

        long start = System.currentTimeMillis();
        log.info("Idempotent processing: orderId={}, eventId={}", event.getOrderId(), event.getEventId());
        long durationMs = System.currentTimeMillis() - start;

        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(event.getEventId())
                .eventType(event.getEventType())
                .topic(topic)
                .partitionNo(partition)
                .kafkaOffset(offset)
                .correlationId(event.getCorrelationId())
                .processedAt(LocalDateTime.now())
                .processingDurationMs(durationMs)
                .build());

        ack.acknowledge();
    }
}
