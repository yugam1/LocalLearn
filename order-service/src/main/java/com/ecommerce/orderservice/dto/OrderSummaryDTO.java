package com.ecommerce.orderservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Class ("DTO") projection populated via a JPQL constructor expression:
 * {@code SELECT new com.ecommerce.orderservice.dto.OrderSummaryDTO(o.id, o.orderNumber, o.totalAmount) FROM Order o}
 * No OrderItem data is ever fetched — the fastest option for list/summary views
 * (see docs/phase1_task4.md and docs/phase1_task6.md).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderSummaryDTO {
    private Long id;
    private String orderNumber;
    private BigDecimal totalAmount;
}
