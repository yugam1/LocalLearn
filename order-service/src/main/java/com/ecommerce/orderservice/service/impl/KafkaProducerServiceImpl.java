package com.ecommerce.orderservice.service.impl;

import com.ecommerce.orderservice.config.KafkaTopicConfig;
import com.ecommerce.orderservice.event.BaseEvent;
import com.ecommerce.orderservice.event.OrderCancelledEvent;
import com.ecommerce.orderservice.event.OrderCreatedEvent;
import com.ecommerce.orderservice.event.OrderUpdatedEvent;
import com.ecommerce.orderservice.model.Order;
import com.ecommerce.orderservice.model.OrderItem;
import com.ecommerce.orderservice.service.KafkaProducerService;
import com.ecommerce.orderservice.util.LoggingUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Async publish with callback (docs/phase2_task10.md, "KafkaProducerService").
 * Every event carries the same routing key convention so
 * {@link com.ecommerce.orderservice.kafka.partitioner.OrderPartitioner} can
 * route it: {@code "vip-<orderId>"} for high-value orders, {@code
 * "bulk-<orderId>"} for high-quantity orders, plain {@code "<orderId>"}
 * otherwise — always ordered per order since the id is part of every key.
 * The active MDC correlation id is propagated both in the event payload and
 * as a raw Kafka header, so consumers can restore it without deserializing
 * the payload first (docs/phase2_task11.md correlation-id requirement).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaProducerServiceImpl implements KafkaProducerService {

    private static final String CORRELATION_ID_HEADER = "correlationId";
    private static final BigDecimal VIP_THRESHOLD = new BigDecimal("5000");
    private static final int BULK_QUANTITY_THRESHOLD = 50;

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Override
    public void publishOrderCreated(Order order) {
        OrderCreatedEvent event = OrderCreatedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("ORDER_CREATED")
                .eventTimestamp(LocalDateTime.now())
                .correlationId(LoggingUtils.getCorrelationId())
                .userId(order.getCustomerEmail())
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .customerName(order.getCustomerName())
                .customerEmail(order.getCustomerEmail())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus())
                .items(toItemEvents(order.getItems()))
                .build();
        send(KafkaTopicConfig.ORDER_CREATED_TOPIC, buildKey(order), event, "ORDER_CREATED", order.getId());
    }

    @Override
    public void publishOrderUpdated(Order order) {
        OrderUpdatedEvent event = OrderUpdatedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("ORDER_UPDATED")
                .eventTimestamp(LocalDateTime.now())
                .correlationId(LoggingUtils.getCorrelationId())
                .userId(order.getCustomerEmail())
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .customerEmail(order.getCustomerEmail())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus())
                .build();
        send(KafkaTopicConfig.ORDER_UPDATED_TOPIC, buildKey(order), event, "ORDER_UPDATED", order.getId());
    }

    @Override
    public void publishOrderCancelled(Order order, String reason) {
        OrderCancelledEvent event = OrderCancelledEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("ORDER_CANCELLED")
                .eventTimestamp(LocalDateTime.now())
                .correlationId(LoggingUtils.getCorrelationId())
                .userId(order.getCustomerEmail())
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .customerEmail(order.getCustomerEmail())
                .reason(reason)
                .build();
        send(KafkaTopicConfig.ORDER_CANCELLED_TOPIC, buildKey(order), event, "ORDER_CANCELLED", order.getId());
    }

    private void send(String topic, String key, BaseEvent event, String eventType, Long orderId) {
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, null, key, event);
        if (event.getCorrelationId() != null) {
            record.headers().add(new RecordHeader(
                    CORRELATION_ID_HEADER, event.getCorrelationId().getBytes(StandardCharsets.UTF_8)));
        }
        kafkaTemplate.send(record).whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("Published {}: orderId={}, partition={}, offset={}",
                        eventType, orderId,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            } else {
                log.error("Failed to publish {}: orderId={}", eventType, orderId, ex);
            }
        });
    }

    /** VIP/bulk prefix drives {@link com.ecommerce.orderservice.kafka.partitioner.OrderPartitioner} routing. */
    private String buildKey(Order order) {
        String base = order.getId().toString();
        if (order.getTotalAmount() != null && order.getTotalAmount().compareTo(VIP_THRESHOLD) >= 0) {
            return "vip-" + base;
        }
        int totalQuantity = order.getItems().stream().mapToInt(OrderItem::getQuantity).sum();
        if (totalQuantity >= BULK_QUANTITY_THRESHOLD) {
            return "bulk-" + base;
        }
        return base;
    }

    private List<OrderCreatedEvent.OrderItemEvent> toItemEvents(List<OrderItem> items) {
        return items.stream()
                .map(item -> new OrderCreatedEvent.OrderItemEvent(
                        item.getProductName(), item.getQuantity(), item.getUnitPrice()))
                .collect(Collectors.toList());
    }
}
