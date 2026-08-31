package com.ecommerce.orderservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Idempotency ledger: one row per successfully-processed Kafka event id.
 * {@link com.ecommerce.orderservice.service.IdempotentKafkaConsumerService}
 * checks this table before processing so at-least-once redelivery never
 * results in double-processing. See docs/phase2_task11.md, "Idempotent
 * Consumer". Columns "partition" and "offset" are Kafka terms but reserved
 * words in PostgreSQL, hence the renamed columns below.
 */
@Entity
@Table(name = "processed_events",
        indexes = @Index(name = "idx_processed_events_event_id", columnList = "event_id", unique = true))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 100)
    private String eventId;

    @Column(name = "event_type", length = 50)
    private String eventType;

    @Column(name = "topic", length = 100)
    private String topic;

    @Column(name = "partition_no")
    private Integer partitionNo;

    @Column(name = "kafka_offset")
    private Long kafkaOffset;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    @Column(name = "processing_duration_ms")
    private Long processingDurationMs;
}
