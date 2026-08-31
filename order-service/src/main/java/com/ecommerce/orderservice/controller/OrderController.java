package com.ecommerce.orderservice.controller;

import com.ecommerce.orderservice.dto.OrderSummaryDTO;
import com.ecommerce.orderservice.dto.projection.OrderSummary;
import com.ecommerce.orderservice.dto.projection.OrderWithItemCount;
import com.ecommerce.orderservice.dto.request.OrderRequest;
import com.ecommerce.orderservice.dto.response.BatchOrderResult;
import com.ecommerce.orderservice.dto.response.OrderResponse;
import com.ecommerce.orderservice.model.OrderStatus;
import com.ecommerce.orderservice.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody OrderRequest request) {
        OrderResponse response = orderService.createOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/batch")
    public ResponseEntity<List<BatchOrderResult>> createOrdersBatch(
            @Valid @RequestBody List<OrderRequest> requests) {
        return ResponseEntity.ok(orderService.createOrdersBatch(requests));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.getOrderById(id));
    }

    @GetMapping("/by-number/{orderNumber}")
    public ResponseEntity<OrderResponse> getOrderByNumber(@PathVariable String orderNumber) {
        return ResponseEntity.ok(orderService.getOrderByOrderNumber(orderNumber));
    }

    @GetMapping
    public ResponseEntity<Page<OrderResponse>> searchOrders(
            @RequestParam(required = false) String customerEmail,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            Pageable pageable) {
        return ResponseEntity.ok(
                orderService.searchOrders(customerEmail, status, minAmount, maxAmount, pageable));
    }

    @PutMapping("/{id}")
    public ResponseEntity<OrderResponse> updateOrder(@PathVariable Long id,
                                                       @Valid @RequestBody OrderRequest request) {
        return ResponseEntity.ok(orderService.updateOrder(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteOrder(@PathVariable Long id) {
        orderService.deleteOrder(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/summaries")
    public ResponseEntity<List<OrderSummary>> getSummaries() {
        return ResponseEntity.ok(orderService.getOrderSummaries());
    }

    @GetMapping("/summary-report")
    public ResponseEntity<List<OrderSummaryDTO>> getSummaryReport() {
        return ResponseEntity.ok(orderService.getOrderSummaryDtos());
    }

    @GetMapping("/item-counts")
    public ResponseEntity<List<OrderWithItemCount>> getItemCounts() {
        return ResponseEntity.ok(orderService.getOrderItemCounts());
    }

    @GetMapping("/revenue")
    public ResponseEntity<BigDecimal> getRevenue(@RequestParam String date) {
        LocalDate day = LocalDate.parse(date);
        LocalDateTime start = day.atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        return ResponseEntity.ok(orderService.calculateRevenueBetween(start, end));
    }
}
