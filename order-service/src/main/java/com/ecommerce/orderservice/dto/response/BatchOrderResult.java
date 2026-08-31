package com.ecommerce.orderservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a single order within a batch-create request. Each batch item is
 * processed in its own {@code Propagation.NESTED} transaction (savepoint) so
 * one failure doesn't roll back siblings that already succeeded — see
 * docs/phase1_task5.md, NESTED propagation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchOrderResult {
    private int requestIndex;
    private boolean success;
    private OrderResponse order;
    private String errorMessage;
}
