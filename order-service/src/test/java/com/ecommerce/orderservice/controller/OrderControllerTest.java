package com.ecommerce.orderservice.controller;

import com.ecommerce.orderservice.dto.OrderSummaryDTO;
import com.ecommerce.orderservice.dto.projection.OrderSummary;
import com.ecommerce.orderservice.dto.projection.OrderWithItemCount;
import com.ecommerce.orderservice.dto.request.OrderRequest;
import com.ecommerce.orderservice.dto.response.OrderItemResponse;
import com.ecommerce.orderservice.dto.response.OrderResponse;
import com.ecommerce.orderservice.exception.ConcurrentModificationException;
import com.ecommerce.orderservice.exception.InsufficientStockException;
import com.ecommerce.orderservice.exception.OrderNotFoundException;
import com.ecommerce.orderservice.model.OrderStatus;
import com.ecommerce.orderservice.service.OrderService;
import com.ecommerce.orderservice.support.TestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 19 — web-layer slice test (docs/phase3_tasks13_to_18.md).
 *
 * <p>{@code @WebMvcTest} loads only the MVC infrastructure: this controller,
 * {@code @ControllerAdvice} beans, Jackson, and registered servlet
 * {@code Filter}s. No JPA, no Kafka, no DB — so these tests assert HTTP
 * contract only (status, headers, JSON shape, validation, exception mapping)
 * and the service is a {@code @MockBean}.
 */
@WebMvcTest(OrderController.class)
@DisplayName("OrderController (web slice)")
class OrderControllerTest {

    private static final String BASE = "/api/v1/orders";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrderService orderService;

    private static OrderResponse response(Long id, String orderNumber) {
        return OrderResponse.builder()
                .id(id)
                .orderNumber(orderNumber)
                .customerName(TestData.CUSTOMER_NAME)
                .customerEmail(TestData.CUSTOMER_EMAIL)
                .status(OrderStatus.PENDING)
                .totalAmount(TestData.EXPECTED_TOTAL)
                .orderDate(LocalDateTime.of(2026, 1, 1, 10, 0))
                .lastUpdated(LocalDateTime.of(2026, 1, 1, 10, 0))
                .items(Collections.singletonList(OrderItemResponse.builder()
                        .id(10L)
                        .productName("Laptop")
                        .quantity(1)
                        .unitPrice(new BigDecimal("2999.00"))
                        .lineTotal(new BigDecimal("2999.00"))
                        .build()))
                .build();
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    @Test
    void createOrder_valid_returns201WithBody() throws Exception {
        when(orderService.createOrder(any(OrderRequest.class))).thenReturn(response(1L, "ORD-123"));

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.orderRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.orderNumber").value("ORD-123"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(3158.98))
                .andExpect(jsonPath("$.items[0].productName").value("Laptop"))
                .andExpect(jsonPath("$.items[0].lineTotal").value(2999.00));
    }

    /** Proves MDCFilter is in the request path and echoes a correlation id back. */
    @Test
    void everyResponse_carriesACorrelationIdHeader() throws Exception {
        when(orderService.getOrderById(1L)).thenReturn(response(1L, "ORD-1"));

        mockMvc.perform(get(BASE + "/1"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-ID"));
    }

    @Test
    void createOrder_echoesSuppliedCorrelationId() throws Exception {
        when(orderService.createOrder(any(OrderRequest.class))).thenReturn(response(1L, "ORD-1"));

        mockMvc.perform(post(BASE)
                        .header("X-Correlation-ID", "REQ-test-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.orderRequest())))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Correlation-ID", "REQ-test-123"));
    }

    @Test
    void createOrder_blankCustomerName_returns400WithFieldError() throws Exception {
        OrderRequest invalid = TestData.orderRequest("", TestData.CUSTOMER_EMAIL);

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed for request"))
                .andExpect(jsonPath("$.path").value(BASE))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("customerName")));

        verify(orderService, never()).createOrder(any(OrderRequest.class));
    }

    @Test
    void createOrder_malformedEmail_returns400() throws Exception {
        OrderRequest invalid = TestData.orderRequest(TestData.CUSTOMER_NAME, "not-an-email");

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("customerEmail")));
    }

    @Test
    void createOrder_noItems_returns400() throws Exception {
        OrderRequest invalid = TestData.orderRequest();
        invalid.setItems(Collections.emptyList());

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("items")));
    }

    /** @Valid cascades into the list elements, so nested paths appear as items[0].quantity. */
    @Test
    void createOrder_invalidNestedItem_returns400WithIndexedFieldPath() throws Exception {
        OrderRequest invalid = TestData.orderRequest();
        invalid.setItems(Collections.singletonList(TestData.itemRequest("Laptop", 0, "2999.00")));

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("items[0].quantity")));
    }

    @Test
    void getOrder_notFound_returns404WithDomainMessage() throws Exception {
        when(orderService.getOrderById(99L)).thenThrow(new OrderNotFoundException(99L));

        mockMvc.perform(get(BASE + "/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Order not found with ID: 99"))
                .andExpect(jsonPath("$.path").value(BASE + "/99"));
    }

    @Test
    void getOrderByNumber_delegatesToService() throws Exception {
        when(orderService.getOrderByOrderNumber("ORD-1")).thenReturn(response(1L, "ORD-1"));

        mockMvc.perform(get(BASE + "/by-number/ORD-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value("ORD-1"));
    }

    @Test
    void searchOrders_returnsPagedBody() throws Exception {
        Pageable pageable = PageRequest.of(0, 20);
        Page<OrderResponse> page = new PageImpl<>(
                Collections.singletonList(response(1L, "ORD-1")), pageable, 1);
        when(orderService.searchOrders(eq(TestData.CUSTOMER_EMAIL), eq(OrderStatus.PENDING),
                any(), any(), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get(BASE)
                        .param("customerEmail", TestData.CUSTOMER_EMAIL)
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void searchOrders_unknownStatusValue_returns500FromTheCatchAllHandler() throws Exception {
        // Documents current behaviour: the enum bind failure is not mapped to 400.
        mockMvc.perform(get(BASE).param("status", "NOT_A_STATUS"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void updateOrder_optimisticLockConflict_returns409() throws Exception {
        when(orderService.updateOrder(eq(1L), any(OrderRequest.class)))
                .thenThrow(new ConcurrentModificationException("Order", 1L));

        mockMvc.perform(put(BASE + "/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.orderRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("The record was modified by another user. Please refresh and retry."));
    }

    @Test
    void createOrder_insufficientStock_returns422() throws Exception {
        when(orderService.createOrder(any(OrderRequest.class)))
                .thenThrow(new InsufficientStockException("Laptop", 5, 1));

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.orderRequest())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(
                        "Insufficient stock for 'Laptop'. Requested: 5, Available: 1"));
    }

    @Test
    void deleteOrder_returns204AndNoBody() throws Exception {
        doNothing().when(orderService).deleteOrder(1L);

        mockMvc.perform(delete(BASE + "/1"))
                .andExpect(status().isNoContent());

        verify(orderService).deleteOrder(1L);
    }

    @Test
    void deleteOrder_notFound_returns404() throws Exception {
        doThrow(new OrderNotFoundException(99L)).when(orderService).deleteOrder(99L);

        mockMvc.perform(delete(BASE + "/99"))
                .andExpect(status().isNotFound());
    }

    /**
     * Interface projections are JDK proxies at runtime; here a plain stub
     * suffices, and the point of the test is that Jackson serialises the
     * getters into the expected JSON.
     */
    @Test
    void getSummaries_serialisesInterfaceProjection() throws Exception {
        OrderSummary summary = new OrderSummary() {
            @Override
            public Long getId() {
                return 1L;
            }

            @Override
            public String getOrderNumber() {
                return "ORD-1";
            }

            @Override
            public BigDecimal getTotalAmount() {
                return new BigDecimal("3158.98");
            }

            @Override
            public OrderStatus getStatus() {
                return OrderStatus.PENDING;
            }
        };
        when(orderService.getOrderSummaries()).thenReturn(Collections.singletonList(summary));

        mockMvc.perform(get(BASE + "/summaries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].orderNumber").value("ORD-1"))
                .andExpect(jsonPath("$[0].totalAmount").value(3158.98))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    void getSummaryReport_serialisesDtoProjectionWithoutItems() throws Exception {
        when(orderService.getOrderSummaryDtos()).thenReturn(Collections.singletonList(
                OrderSummaryDTO.builder()
                        .id(1L)
                        .orderNumber("ORD-1")
                        .totalAmount(new BigDecimal("3158.98"))
                        .build()));

        mockMvc.perform(get(BASE + "/summary-report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderNumber").value("ORD-1"))
                .andExpect(jsonPath("$[0].items").doesNotExist());
    }

    @Test
    void getItemCounts_serialisesAggregateProjection() throws Exception {
        OrderWithItemCount count = new OrderWithItemCount() {
            @Override
            public Long getId() {
                return 1L;
            }

            @Override
            public String getOrderNumber() {
                return "ORD-1";
            }

            @Override
            public Long getItemCount() {
                return 2L;
            }
        };
        when(orderService.getOrderItemCounts()).thenReturn(Collections.singletonList(count));

        mockMvc.perform(get(BASE + "/item-counts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].itemCount").value(2));
    }

    @Test
    void getRevenue_parsesDateAndReturnsAmount() throws Exception {
        when(orderService.calculateRevenueBetween(
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 2, 0, 0)))
                .thenReturn(new BigDecimal("4200.00"));

        mockMvc.perform(get(BASE + "/revenue").param("date", "2026-01-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(4200.00));
    }

    /** An unmapped exception must never leak a stack trace or internal message. */
    @Test
    void unexpectedServiceFailure_returns500WithGenericMessage() throws Exception {
        when(orderService.getOrderById(1L))
                .thenThrow(new IllegalStateException("connection pool exhausted at com.zaxxer..."));

        mockMvc.perform(get(BASE + "/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message")
                        .value("An unexpected error occurred. Please contact support."));
    }

    @Test
    void createOrder_malformedJson_returns500FromTheCatchAllHandler() throws Exception {
        // Documents current behaviour: HttpMessageNotReadableException is unmapped.
        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not json"))
                .andExpect(status().isInternalServerError());

        verify(orderService, never()).createOrder(any(OrderRequest.class));
    }
}
