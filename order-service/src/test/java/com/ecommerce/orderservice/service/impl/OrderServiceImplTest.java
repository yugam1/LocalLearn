package com.ecommerce.orderservice.service.impl;

import com.ecommerce.orderservice.dto.request.OrderRequest;
import com.ecommerce.orderservice.dto.response.BatchOrderResult;
import com.ecommerce.orderservice.dto.response.OrderResponse;
import com.ecommerce.orderservice.exception.ConcurrentModificationException;
import com.ecommerce.orderservice.exception.InsufficientStockException;
import com.ecommerce.orderservice.exception.OrderNotFoundException;
import com.ecommerce.orderservice.model.Order;
import com.ecommerce.orderservice.model.OrderItem;
import com.ecommerce.orderservice.model.OrderStatus;
import com.ecommerce.orderservice.repository.OrderRepository;
import com.ecommerce.orderservice.service.AuditService;
import com.ecommerce.orderservice.service.InventoryService;
import com.ecommerce.orderservice.service.KafkaProducerService;
import com.ecommerce.orderservice.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Task 16 — service-layer unit tests with Mockito (docs/phase3_tasks13_to_18.md).
 *
 * <p>No Spring context: {@code @InjectMocks} constructs the real
 * {@link OrderServiceImpl} and wires the {@code @Mock}s through its
 * {@code @RequiredArgsConstructor}. That makes every test sub-millisecond, but
 * it also means {@code @Transactional} is <em>not</em> in play here — proxy
 * behaviour (propagation, rollback, savepoints) can only be verified in the
 * integration tests.
 *
 * <p>Lives in {@code ...service.impl} because {@link OrderNestedTransactionHelper}
 * is package-private.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderServiceImpl (unit)")
class OrderServiceImplTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private InventoryService inventoryService;
    @Mock
    private AuditService auditService;
    @Mock
    private OrderNestedTransactionHelper nestedTransactionHelper;
    @Mock
    private KafkaProducerService kafkaProducerService;

    @Captor
    private ArgumentCaptor<Order> orderCaptor;

    @InjectMocks
    private OrderServiceImpl orderService;

    @Nested
    @DisplayName("createOrder")
    class CreateOrder {

        @Test
        void validRequest_persistsOrderAndReturnsResponse() {
            when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
                Order toSave = invocation.getArgument(0);
                toSave.setId(1L);
                return toSave;
            });

            OrderResponse response = orderService.createOrder(TestData.orderRequest());

            assertThat(response.getId()).isEqualTo(1L);
            assertThat(response.getOrderNumber()).startsWith("ORD-");
            assertThat(response.getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(response.getCustomerEmail()).isEqualTo(TestData.CUSTOMER_EMAIL);
            assertThat(response.getItems()).hasSize(2);
            assertThat(response.getTotalAmount()).isEqualByComparingTo(TestData.EXPECTED_TOTAL);
        }

        @Test
        void validRequest_buildsOrderWithPendingStatusAndBothSidesOfRelationshipSet() {
            when(orderRepository.save(any(Order.class))).thenAnswer(returnsFirstArg());

            orderService.createOrder(TestData.orderRequest());

            verify(orderRepository).save(orderCaptor.capture());
            Order persisted = orderCaptor.getValue();
            assertThat(persisted.getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(persisted.getCustomerName()).isEqualTo(TestData.CUSTOMER_NAME);
            assertThat(persisted.getTotalAmount()).isEqualByComparingTo(TestData.EXPECTED_TOTAL);
            // addItem() must maintain the inverse side, or the FK is null on insert.
            assertThat(persisted.getItems())
                    .hasSize(2)
                    .allSatisfy(item -> assertThat(item.getOrder()).isSameAs(persisted));
        }

        /**
         * Inventory is reserved with Propagation.MANDATORY, so it must be
         * called before the save that would otherwise commit an unreserved
         * order; the audit and Kafka calls are deliberately after the save
         * because both need the generated id.
         */
        @Test
        void validRequest_reservesInventoryBeforeSaveThenAuditsAndPublishes() {
            when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
                Order toSave = invocation.getArgument(0);
                toSave.setId(42L);
                return toSave;
            });

            orderService.createOrder(TestData.orderRequest());

            InOrder ordered = inOrder(inventoryService, orderRepository, auditService, kafkaProducerService);
            ordered.verify(inventoryService).reserveInventoryItems(anyList());
            ordered.verify(orderRepository).save(any(Order.class));
            ordered.verify(auditService).logOrderCreated(42L, TestData.CUSTOMER_EMAIL);
            ordered.verify(kafkaProducerService).publishOrderCreated(any(Order.class));
        }

        @Test
        void inventoryReservationFails_propagatesAndSkipsSaveAuditAndPublish() {
            org.mockito.Mockito.doThrow(new InsufficientStockException("Laptop", 1, 0))
                    .when(inventoryService).reserveInventoryItems(anyList());

            assertThatThrownBy(() -> orderService.createOrder(TestData.orderRequest()))
                    .isInstanceOf(InsufficientStockException.class);

            verify(orderRepository, never()).save(any(Order.class));
            verifyNoInteractions(auditService, kafkaProducerService);
        }

        @Test
        @SuppressWarnings("unchecked")
        void itemsAreReservedWithTheQuantitiesFromTheRequest() {
            when(orderRepository.save(any(Order.class))).thenAnswer(returnsFirstArg());
            ArgumentCaptor<List<OrderItem>> itemsCaptor =
                    ArgumentCaptor.forClass((Class<List<OrderItem>>) (Class<?>) List.class);

            orderService.createOrder(TestData.orderRequest());

            verify(inventoryService).reserveInventoryItems(itemsCaptor.capture());
            assertThat(itemsCaptor.getValue())
                    .extracting(OrderItem::getProductName, OrderItem::getQuantity)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("Laptop", 1),
                            org.assertj.core.groups.Tuple.tuple("Mouse", 2));
        }
    }

    @Nested
    @DisplayName("read operations")
    class Reads {

        @Test
        void getOrderById_found_mapsToResponse() {
            Order order = TestData.order(7L, "ORD-7", OrderStatus.CONFIRMED);
            when(orderRepository.findByIdWithItems(7L)).thenReturn(Optional.of(order));

            OrderResponse response = orderService.getOrderById(7L);

            assertThat(response.getId()).isEqualTo(7L);
            assertThat(response.getOrderNumber()).isEqualTo("ORD-7");
            assertThat(response.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(response.getItems())
                    .extracting(item -> item.getLineTotal())
                    .containsExactly(new BigDecimal("2999.00"), new BigDecimal("159.98"));
        }

        @Test
        void getOrderById_notFound_throwsWithIdInMessage() {
            when(orderRepository.findByIdWithItems(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.getOrderById(999L))
                    .isInstanceOf(OrderNotFoundException.class)
                    .hasMessageContaining("999");

            verifyNoInteractions(kafkaProducerService, auditService);
        }

        @Test
        void getOrderByOrderNumber_notFound_throwsWithOrderNumberInMessage() {
            when(orderRepository.findWithItemsByOrderNumber("ORD-MISSING")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.getOrderByOrderNumber("ORD-MISSING"))
                    .isInstanceOf(OrderNotFoundException.class)
                    .hasMessageContaining("ORD-MISSING");
        }

        /**
         * searchOrders uses the two-query pagination pattern: page the IDs
         * first, then JOIN FETCH items for those IDs. The fetch query returns
         * rows in whatever order the DB likes, so the service must re-impose
         * the page order — that is what this asserts.
         */
        @Test
        void searchOrders_restoresPageOrderAfterTheJoinFetchQuery() {
            Pageable pageable = PageRequest.of(0, 2);
            Order first = TestData.order(1L, "ORD-1", OrderStatus.PENDING);
            Order second = TestData.order(2L, "ORD-2", OrderStatus.PENDING);

            Page<Order> idPage = new PageImpl<>(Arrays.asList(first, second), pageable, 5);
            when(orderRepository.findAll(ArgumentMatchers.<Specification<Order>>any(), eq(pageable)))
                    .thenReturn(idPage);
            // Deliberately reversed, as a real DB may return them.
            when(orderRepository.findByIdInWithItems(Arrays.asList(1L, 2L)))
                    .thenReturn(Arrays.asList(second, first));

            Page<OrderResponse> result = orderService.searchOrders(
                    TestData.CUSTOMER_EMAIL, OrderStatus.PENDING, null, null, pageable);

            assertThat(result.getContent()).extracting(OrderResponse::getId).containsExactly(1L, 2L);
            assertThat(result.getTotalElements()).isEqualTo(5);
        }

        @Test
        void searchOrders_dropsIdsTheFetchQueryDidNotReturn() {
            Pageable pageable = PageRequest.of(0, 2);
            Order first = TestData.order(1L, "ORD-1", OrderStatus.PENDING);
            Order deletedConcurrently = TestData.order(2L, "ORD-2", OrderStatus.PENDING);

            when(orderRepository.findAll(ArgumentMatchers.<Specification<Order>>any(), eq(pageable)))
                    .thenReturn(new PageImpl<>(Arrays.asList(first, deletedConcurrently), pageable, 2));
            when(orderRepository.findByIdInWithItems(Arrays.asList(1L, 2L)))
                    .thenReturn(Collections.singletonList(first));

            Page<OrderResponse> result = orderService.searchOrders(null, null, null, null, pageable);

            assertThat(result.getContent()).extracting(OrderResponse::getId).containsExactly(1L);
        }

        @Test
        void calculateRevenueBetween_delegatesToRepository() {
            LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
            LocalDateTime end = start.plusDays(1);
            when(orderRepository.sumTotalAmountBetween(start, end)).thenReturn(new BigDecimal("1500.50"));

            assertThat(orderService.calculateRevenueBetween(start, end))
                    .isEqualByComparingTo("1500.50");
        }
    }

    @Nested
    @DisplayName("updateOrder")
    class UpdateOrder {

        @Test
        void replacesItemsAndRecalculatesTotal() {
            Order existing = TestData.order(3L, "ORD-3", OrderStatus.PENDING);
            when(orderRepository.findByIdWithItems(3L)).thenReturn(Optional.of(existing));
            when(orderRepository.save(any(Order.class))).thenAnswer(returnsFirstArg());

            OrderRequest request = OrderRequest.builder()
                    .customerName("Jane Roe")
                    .customerEmail("jane@example.com")
                    .items(Collections.singletonList(TestData.itemRequest("Keyboard", 3, "50.00")))
                    .build();

            OrderResponse response = orderService.updateOrder(3L, request);

            assertThat(response.getCustomerName()).isEqualTo("Jane Roe");
            assertThat(response.getCustomerEmail()).isEqualTo("jane@example.com");
            assertThat(response.getItems()).hasSize(1);
            assertThat(response.getTotalAmount()).isEqualByComparingTo("150.00");
            // The order number is immutable across an update.
            assertThat(response.getOrderNumber()).isEqualTo("ORD-3");
        }

        @Test
        void notFound_throwsBeforeTouchingTheEntity() {
            when(orderRepository.findByIdWithItems(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.updateOrder(404L, TestData.orderRequest()))
                    .isInstanceOf(OrderNotFoundException.class)
                    .hasMessageContaining("404");

            verify(orderRepository, never()).save(any(Order.class));
        }

        /** @Version conflict must surface as a 409-mapped domain exception, not a raw Spring one. */
        @Test
        void optimisticLockConflict_translatedToConcurrentModificationException() {
            Order existing = TestData.order(5L, "ORD-5", OrderStatus.PENDING);
            when(orderRepository.findByIdWithItems(5L)).thenReturn(Optional.of(existing));
            when(orderRepository.save(any(Order.class)))
                    .thenThrow(new OptimisticLockingFailureException("row was updated by another transaction"));

            assertThatThrownBy(() -> orderService.updateOrder(5L, TestData.orderRequest()))
                    .isInstanceOf(ConcurrentModificationException.class)
                    .hasMessageContaining("Order")
                    .hasMessageContaining("ID: 5");
        }
    }

    @Nested
    @DisplayName("deleteOrder")
    class DeleteOrder {

        @Test
        void found_deletesThenAudits() {
            Order existing = TestData.order(8L, "ORD-8", OrderStatus.PENDING);
            when(orderRepository.findByIdWithItems(8L)).thenReturn(Optional.of(existing));

            orderService.deleteOrder(8L);

            InOrder ordered = inOrder(orderRepository, auditService);
            ordered.verify(orderRepository).delete(existing);
            ordered.verify(auditService).logOrderDeleted(eq(8L), eq(TestData.CUSTOMER_EMAIL), anyString());
        }

        @Test
        void notFound_throwsAndDeletesNothing() {
            when(orderRepository.findByIdWithItems(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.deleteOrder(404L))
                    .isInstanceOf(OrderNotFoundException.class);

            verify(orderRepository, never()).delete(any(Order.class));
            verifyNoInteractions(auditService);
        }
    }

    @Nested
    @DisplayName("createOrdersBatch")
    class CreateOrdersBatch {

        /**
         * The point of the NESTED savepoint per element: one bad order must not
         * discard the ones that already succeeded.
         */
        @Test
        void oneElementFails_othersStillSucceedAndResultsKeepRequestIndexes() {
            when(nestedTransactionHelper.createOrderWithSavepoint(any(Order.class)))
                    .thenAnswer(invocation -> {
                        Order toSave = invocation.getArgument(0);
                        toSave.setId(1L);
                        return toSave;
                    })
                    .thenThrow(new InsufficientStockException("Laptop", 5, 1))
                    .thenAnswer(invocation -> {
                        Order toSave = invocation.getArgument(0);
                        toSave.setId(3L);
                        return toSave;
                    });

            List<BatchOrderResult> results = orderService.createOrdersBatch(Arrays.asList(
                    TestData.orderRequest(),
                    TestData.orderRequest(),
                    TestData.orderRequest()));

            assertThat(results).hasSize(3);
            assertThat(results).extracting(BatchOrderResult::getRequestIndex).containsExactly(0, 1, 2);
            assertThat(results).extracting(BatchOrderResult::isSuccess).containsExactly(true, false, true);

            assertThat(results.get(0).getOrder().getId()).isEqualTo(1L);
            assertThat(results.get(1).getOrder()).isNull();
            assertThat(results.get(1).getErrorMessage()).contains("Laptop");
            assertThat(results.get(2).getOrder().getId()).isEqualTo(3L);

            // Only the successful elements are audited.
            verify(auditService, times(2)).logOrderCreated(any(), anyString());
        }

        @Test
        void emptyBatch_returnsEmptyResultsAndTouchesNothing() {
            assertThat(orderService.createOrdersBatch(Collections.emptyList())).isEmpty();
            verifyNoInteractions(nestedTransactionHelper, auditService);
        }
    }
}
