package com.ecommerce.orderservice.service;

import com.ecommerce.orderservice.dto.OrderSummaryDTO;
import com.ecommerce.orderservice.dto.projection.OrderSummary;
import com.ecommerce.orderservice.dto.projection.OrderWithItemCount;
import com.ecommerce.orderservice.dto.request.OrderRequest;
import com.ecommerce.orderservice.dto.response.BatchOrderResult;
import com.ecommerce.orderservice.dto.response.OrderResponse;
import com.ecommerce.orderservice.model.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface OrderService {

    OrderResponse createOrder(OrderRequest request);

    OrderResponse getOrderById(Long id);

    OrderResponse getOrderByOrderNumber(String orderNumber);

    Page<OrderResponse> searchOrders(String customerEmail, OrderStatus status,
                                      BigDecimal minAmount, BigDecimal maxAmount,
                                      Pageable pageable);

    OrderResponse updateOrder(Long id, OrderRequest request);

    void deleteOrder(Long id);

    /** Each element processed in its own NESTED (savepoint) transaction. */
    List<BatchOrderResult> createOrdersBatch(List<OrderRequest> requests);

    List<OrderSummary> getOrderSummaries();

    List<OrderSummaryDTO> getOrderSummaryDtos();

    List<OrderWithItemCount> getOrderItemCounts();

    BigDecimal calculateRevenueBetween(LocalDateTime start, LocalDateTime end);
}
