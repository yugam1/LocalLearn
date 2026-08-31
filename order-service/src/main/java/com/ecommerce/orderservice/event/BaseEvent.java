package com.ecommerce.orderservice.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * Common metadata shared by every Kafka event this service publishes.
 * {@code eventId} is the idempotency key consumers dedupe on (see
 * {@link com.ecommerce.orderservice.model.ProcessedEvent}), while
 * {@code correlationId} lets a single HTTP request be traced through
 * producer and consumer logs (docs/phase2_task10.md, "MDC propagation").
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseEvent {

    private String eventId;
    private String eventType;
    private LocalDateTime eventTimestamp;
    private String correlationId;
    private String userId;
}
