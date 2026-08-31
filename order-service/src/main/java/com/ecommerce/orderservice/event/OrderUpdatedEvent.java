package com.ecommerce.orderservice.event;

import com.ecommerce.orderservice.model.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * Published to {@code order.updated} whenever an existing order's details
 * or status change. See docs/phase2_task10.md, "Event Hierarchy".
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class OrderUpdatedEvent extends BaseEvent {

    private Long orderId;
    private String orderNumber;
    private String customerEmail;
    private BigDecimal totalAmount;
    private OrderStatus status;
}
