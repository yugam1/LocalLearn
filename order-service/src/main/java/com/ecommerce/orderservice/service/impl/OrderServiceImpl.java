package com.ecommerce.orderservice.service.impl;

import com.ecommerce.orderservice.dto.OrderSummaryDTO;
import com.ecommerce.orderservice.dto.projection.OrderSummary;
import com.ecommerce.orderservice.dto.projection.OrderWithItemCount;
import com.ecommerce.orderservice.dto.request.OrderItemRequest;
import com.ecommerce.orderservice.dto.request.OrderRequest;
import com.ecommerce.orderservice.dto.response.BatchOrderResult;
import com.ecommerce.orderservice.dto.response.OrderItemResponse;
import com.ecommerce.orderservice.dto.response.OrderResponse;
import com.ecommerce.orderservice.exception.OrderNotFoundException;
import com.ecommerce.orderservice.model.Order;
import com.ecommerce.orderservice.model.OrderItem;
import com.ecommerce.orderservice.model.OrderStatus;
import com.ecommerce.orderservice.repository.OrderRepository;
import com.ecommerce.orderservice.service.AuditService;
import com.ecommerce.orderservice.service.InventoryService;
import com.ecommerce.orderservice.service.KafkaProducerService;
import com.ecommerce.orderservice.service.OrderService;
import com.ecommerce.orderservice.util.LoggingUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.ecommerce.orderservice.specification.OrderSpecification.hasCustomerEmail;
import static com.ecommerce.orderservice.specification.OrderSpecification.hasStatus;
import static com.ecommerce.orderservice.specification.OrderSpecification.hasTotalAmountBetween;

/**
 * readOnly = true at class level: Hibernate skips dirty checking for every
 * method by default; write methods explicitly override with @Transactional.
 * See docs/phase1_task5.md, Read-Only Transactions.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final InventoryService inventoryService;
    private final AuditService auditService;
    private final OrderNestedTransactionHelper nestedTransactionHelper;
    private final KafkaProducerService kafkaProducerService;

    @Override
    @Transactional
    public OrderResponse createOrder(OrderRequest request) {
        log.info("Creating order for customer: {}", LoggingUtils.maskEmail(request.getCustomerEmail()));

        Order order = buildOrderFromRequest(request);

        // Propagation.MANDATORY — must run inside this active transaction.
        inventoryService.reserveInventoryItems(order.getItems());

        Order saved = orderRepository.save(order);

        // Propagation.REQUIRES_NEW — commits independently even if this
        // transaction later rolls back.
        auditService.logOrderCreated(saved.getId(), saved.getCustomerEmail());

        // Kafka: publish ORDER_CREATED (docs/phase2_task10.md). Best-effort,
        // like the audit call above — not part of a transactional outbox.
        kafkaProducerService.publishOrderCreated(saved);

        log.info("Order created: id={}, orderNumber={}, total={}",
                saved.getId(), saved.getOrderNumber(), saved.getTotalAmount());
        return mapToResponse(saved);
    }

    @Override
    public OrderResponse getOrderById(Long id) {
        return orderRepository.findByIdWithItems(id)
                .map(this::mapToResponse)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    @Override
    public OrderResponse getOrderByOrderNumber(String orderNumber) {
        return orderRepository.findWithItemsByOrderNumber(orderNumber)
                .map(this::mapToResponse)
                .orElseThrow(() -> new OrderNotFoundException(orderNumber));
    }

    @Override
    public Page<OrderResponse> searchOrders(String customerEmail, OrderStatus status,
                                             BigDecimal minAmount, BigDecimal maxAmount,
                                             Pageable pageable) {
        Specification<Order> spec = Specification
                .where(hasCustomerEmail(customerEmail))
                .and(hasStatus(status))
                .and(hasTotalAmountBetween(minAmount, maxAmount));

        // Two-query pattern: DB-level pagination first (no items = no
        // Cartesian product), then JOIN FETCH items for just this page's
        // IDs. See docs/phase1_task6.md, "Paginated JOIN FETCH".
        Page<Order> page = orderRepository.findAll(spec, pageable);
        List<Long> ids = page.getContent().stream().map(Order::getId).collect(Collectors.toList());

        Map<Long, Order> byId = orderRepository.findByIdInWithItems(ids).stream()
                .collect(Collectors.toMap(Order::getId, o -> o, (a, b) -> a, LinkedHashMap::new));

        List<OrderResponse> content = ids.stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .map(this::mapToResponse)
                .collect(Collectors.toList());

        return new PageImpl<>(content, pageable, page.getTotalElements());
    }

    @Override
    @Transactional
    public OrderResponse updateOrder(Long id, OrderRequest request) {
        Order existing = orderRepository.findByIdWithItems(id)
                .orElseThrow(() -> new OrderNotFoundException(id));

        existing.setCustomerName(request.getCustomerName());
        existing.setCustomerEmail(request.getCustomerEmail());
        existing.getItems().clear(); // orphanRemoval deletes old items on flush

        for (OrderItemRequest itemRequest : request.getItems()) {
            existing.addItem(OrderItem.builder()
                    .productName(itemRequest.getProductName())
                    .quantity(itemRequest.getQuantity())
                    .unitPrice(itemRequest.getUnitPrice())
                    .build());
        }
        existing.calculateTotal();

        try {
            Order saved = orderRepository.save(existing);
            return mapToResponse(saved);
        } catch (OptimisticLockingFailureException e) {
            throw new com.ecommerce.orderservice.exception.ConcurrentModificationException("Order", id);
        }
    }

    @Override
    @Transactional
    public void deleteOrder(Long id) {
        Order order = orderRepository.findByIdWithItems(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
        orderRepository.delete(order); // cascade deletes items
        auditService.logOrderDeleted(id, order.getCustomerEmail(), "requested via API");
        log.info("Order deleted: id={}", id);
    }

    @Override
    @Transactional
    public List<BatchOrderResult> createOrdersBatch(List<OrderRequest> requests) {
        List<BatchOrderResult> results = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            OrderRequest request = requests.get(i);
            try {
                Order order = buildOrderFromRequest(request);
                // Each iteration runs in its own NESTED savepoint — a
                // failure here rolls back only this order, not the whole batch.
                Order saved = nestedTransactionHelper.createOrderWithSavepoint(order);
                auditService.logOrderCreated(saved.getId(), saved.getCustomerEmail());
                results.add(BatchOrderResult.builder()
                        .requestIndex(i)
                        .success(true)
                        .order(mapToResponse(saved))
                        .build());
            } catch (Exception e) {
                log.warn("Batch order {} failed: {}", i, e.getMessage());
                results.add(BatchOrderResult.builder()
                        .requestIndex(i)
                        .success(false)
                        .errorMessage(e.getMessage())
                        .build());
            }
        }
        return results;
    }

    @Override
    public List<OrderSummary> getOrderSummaries() {
        return orderRepository.findAllBy();
    }

    @Override
    public List<OrderSummaryDTO> getOrderSummaryDtos() {
        return orderRepository.findAllSummaryDtos();
    }

    @Override
    public List<OrderWithItemCount> getOrderItemCounts() {
        return orderRepository.findOrderItemCounts();
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public BigDecimal calculateRevenueBetween(LocalDateTime start, LocalDateTime end) {
        return orderRepository.sumTotalAmountBetween(start, end);
    }

    private Order buildOrderFromRequest(OrderRequest request) {
        Order order = Order.builder()
                .orderNumber("ORD-" + System.currentTimeMillis() + "-" + (int) (Math.random() * 1000))
                .customerName(request.getCustomerName())
                .customerEmail(request.getCustomerEmail())
                .status(OrderStatus.PENDING)
                .totalAmount(BigDecimal.ZERO)
                .build();

        for (OrderItemRequest itemRequest : request.getItems()) {
            order.addItem(OrderItem.builder()
                    .productName(itemRequest.getProductName())
                    .quantity(itemRequest.getQuantity())
                    .unitPrice(itemRequest.getUnitPrice())
                    .build());
        }
        order.calculateTotal();
        return order;
    }

    private OrderResponse mapToResponse(Order order) {
        List<OrderItemResponse> itemResponses = order.getItems().stream()
                .map(item -> OrderItemResponse.builder()
                        .id(item.getId())
                        .productName(item.getProductName())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .lineTotal(item.getLineTotal())
                        .build())
                .collect(Collectors.toList());

        return OrderResponse.builder()
                .id(order.getId())
                .orderNumber(order.getOrderNumber())
                .customerName(order.getCustomerName())
                .customerEmail(order.getCustomerEmail())
                .status(order.getStatus())
                .totalAmount(order.getTotalAmount())
                .orderDate(order.getOrderDate())
                .lastUpdated(order.getLastUpdated())
                .items(itemResponses)
                .build();
    }
}
