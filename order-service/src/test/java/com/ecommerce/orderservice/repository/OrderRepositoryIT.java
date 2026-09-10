package com.ecommerce.orderservice.repository;

import com.ecommerce.orderservice.dto.OrderSummaryDTO;
import com.ecommerce.orderservice.dto.projection.OrderSummary;
import com.ecommerce.orderservice.dto.projection.OrderWithItemCount;
import com.ecommerce.orderservice.model.Order;
import com.ecommerce.orderservice.model.OrderStatus;
import com.ecommerce.orderservice.support.AbstractPostgresIT;
import com.ecommerce.orderservice.support.TestData;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 18 — repository slice against a real PostgreSQL container
 * (docs/phase3_tasks13_to_18.md).
 *
 * <p>{@code @DataJpaTest} loads only JPA: entities, repositories, Hibernate,
 * and a {@link TestEntityManager}. {@code replace = NONE} keeps Boot from
 * swapping in an embedded H2 — the whole point is to run the real dialect, so
 * that {@code JOIN FETCH}, {@code GROUP BY}, and the unique constraint behave
 * the way they will in production.
 *
 * <p>Each test method runs in a transaction that is rolled back afterwards,
 * so no cleanup is needed between tests.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("OrderRepository (JPA slice, real PostgreSQL)")
class OrderRepositoryIT extends AbstractPostgresIT {

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private TestEntityManager entityManager;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    @BeforeEach
    void resetStatistics() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
    }

    private Order persist(Order order) {
        return entityManager.persistAndFlush(order);
    }

    /** Detach everything so the next read must hit the database, not the L1 cache. */
    private void detachAll() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void findByIdWithItems_loadsOrderAndItemsInASingleQuery() {
        Order persisted = persist(TestData.order(null, "ORD-JOIN-1", OrderStatus.PENDING));
        detachAll();
        statistics.clear();

        Optional<Order> found = orderRepository.findByIdWithItems(persisted.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getItems()).hasSize(2);
        // The JOIN FETCH is the whole point: 1 query, not 1 + 1 lazy load.
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    /** The contrast case that makes the previous assertion meaningful. */
    @Test
    void findById_thenTouchingItems_costsASecondQuery() {
        Order persisted = persist(TestData.order(null, "ORD-LAZY-1", OrderStatus.PENDING));
        detachAll();
        statistics.clear();

        Order found = orderRepository.findById(persisted.getId()).orElseThrow();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);

        assertThat(found.getItems()).hasSize(2); // triggers the lazy collection load
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void findWithItemsByOrderNumber_entityGraphAlsoFetchesItemsEagerly() {
        persist(TestData.order(null, "ORD-GRAPH-1", OrderStatus.PENDING));
        detachAll();
        statistics.clear();

        Optional<Order> found = orderRepository.findWithItemsByOrderNumber("ORD-GRAPH-1");

        assertThat(found).isPresent();
        assertThat(found.get().getItems()).hasSize(2);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void findByIdInWithItems_fetchesEveryRequestedOrderInOneQuery() {
        Order first = persist(TestData.order(null, "ORD-IN-1", OrderStatus.PENDING));
        Order second = persist(TestData.order(null, "ORD-IN-2", OrderStatus.PENDING));
        detachAll();
        statistics.clear();

        List<Order> found = orderRepository.findByIdInWithItems(
                Arrays.asList(first.getId(), second.getId()));

        assertThat(found).hasSize(2);
        assertThat(found).allSatisfy(order -> assertThat(order.getItems()).hasSize(2));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void findByStatus_returnsOnlyMatchingOrders() {
        persist(TestData.order(null, "ORD-S-1", OrderStatus.PENDING));
        persist(TestData.order(null, "ORD-S-2", OrderStatus.CONFIRMED));
        persist(TestData.order(null, "ORD-S-3", OrderStatus.PENDING));
        detachAll();

        assertThat(orderRepository.findByStatus(OrderStatus.PENDING))
                .extracting(Order::getOrderNumber)
                .containsExactlyInAnyOrder("ORD-S-1", "ORD-S-3");
        assertThat(orderRepository.countByStatus(OrderStatus.CONFIRMED)).isEqualTo(1);
    }

    @Test
    void findByCustomerEmail_matchesExactly() {
        Order order = TestData.order(null, "ORD-E-1", OrderStatus.PENDING);
        order.setCustomerEmail("someone.else@example.com");
        persist(order);
        persist(TestData.order(null, "ORD-E-2", OrderStatus.PENDING));
        detachAll();

        assertThat(orderRepository.findByCustomerEmail("someone.else@example.com"))
                .extracting(Order::getOrderNumber)
                .containsExactly("ORD-E-1");
    }

    @Test
    void orderNumber_isUnique() {
        persist(TestData.order(null, "ORD-DUP", OrderStatus.PENDING));

        assertThatThrownBy(() -> persist(TestData.order(null, "ORD-DUP", OrderStatus.PENDING)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void existsByOrderNumber_reflectsPersistedState() {
        persist(TestData.order(null, "ORD-EX-1", OrderStatus.PENDING));
        detachAll();

        assertThat(orderRepository.existsByOrderNumber("ORD-EX-1")).isTrue();
        assertThat(orderRepository.existsByOrderNumber("ORD-NOPE")).isFalse();
    }

    @Test
    void findAllSummaryDtos_projectsWithoutFetchingItems() {
        persist(TestData.order(null, "ORD-DTO-1", OrderStatus.PENDING));
        detachAll();
        statistics.clear();

        List<OrderSummaryDTO> summaries = orderRepository.findAllSummaryDtos();

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).getOrderNumber()).isEqualTo("ORD-DTO-1");
        assertThat(summaries.get(0).getTotalAmount()).isEqualByComparingTo(TestData.EXPECTED_TOTAL);
        // A constructor expression never touches order_items.
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void findAllBy_returnsInterfaceProjections() {
        persist(TestData.order(null, "ORD-PROJ-1", OrderStatus.CONFIRMED));
        detachAll();

        List<OrderSummary> summaries = orderRepository.findAllBy();

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).getOrderNumber()).isEqualTo("ORD-PROJ-1");
        assertThat(summaries.get(0).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void findOrderItemCounts_aggregatesItemsPerOrder() {
        persist(TestData.order(null, "ORD-CNT-2", OrderStatus.PENDING));           // 2 items
        persist(TestData.emptyOrder(null, "ORD-CNT-0", OrderStatus.PENDING));      // 0 items
        detachAll();

        List<OrderWithItemCount> counts = orderRepository.findOrderItemCounts();

        assertThat(counts)
                .extracting(OrderWithItemCount::getOrderNumber, OrderWithItemCount::getItemCount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("ORD-CNT-2", 2L),
                        // LEFT JOIN, so an order with no items still appears — with 0.
                        org.assertj.core.groups.Tuple.tuple("ORD-CNT-0", 0L));
    }

    @Test
    void sumTotalAmountBetween_sumsOnlyOrdersInsideTheWindow() {
        persist(TestData.order(null, "ORD-REV-1", OrderStatus.PENDING));
        persist(TestData.order(null, "ORD-REV-2", OrderStatus.PENDING));
        detachAll();

        LocalDateTime start = LocalDateTime.now().minusMinutes(5);
        LocalDateTime end = LocalDateTime.now().plusMinutes(5);

        assertThat(orderRepository.sumTotalAmountBetween(start, end))
                .isEqualByComparingTo(TestData.EXPECTED_TOTAL.multiply(BigDecimal.valueOf(2)));
    }

    @Test
    void sumTotalAmountBetween_emptyWindow_returnsZeroNotNull() {
        persist(TestData.order(null, "ORD-REV-3", OrderStatus.PENDING));
        detachAll();

        // COALESCE in the query is what makes this 0 instead of a null NPE.
        assertThat(orderRepository.sumTotalAmountBetween(
                LocalDateTime.now().minusYears(2), LocalDateTime.now().minusYears(1)))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void deletingAnOrder_cascadesToItsItems() {
        Order persisted = persist(TestData.order(null, "ORD-DEL-1", OrderStatus.PENDING));
        Long id = persisted.getId();
        detachAll();

        orderRepository.delete(orderRepository.findByIdWithItems(id).orElseThrow());
        detachAll();

        assertThat(orderRepository.findById(id)).isEmpty();
        Long remainingItems = (Long) entityManager.getEntityManager()
                .createQuery("SELECT COUNT(i) FROM OrderItem i WHERE i.order.id = :id")
                .setParameter("id", id)
                .getSingleResult();
        assertThat(remainingItems).isZero();
    }

    @Test
    void clearingTheItemCollection_orphanRemovesTheRows() {
        Order persisted = persist(TestData.order(null, "ORD-ORPH-1", OrderStatus.PENDING));
        Long id = persisted.getId();
        detachAll();

        Order loaded = orderRepository.findByIdWithItems(id).orElseThrow();
        loaded.getItems().clear();
        loaded.calculateTotal();
        orderRepository.save(loaded);
        detachAll();

        assertThat(orderRepository.findByIdWithItems(id).orElseThrow().getItems()).isEmpty();
    }

    @Test
    void version_startsAtZeroAndIncrementsOnUpdate() {
        Order persisted = persist(TestData.order(null, "ORD-VER-1", OrderStatus.PENDING));
        assertThat(persisted.getVersion()).isZero();

        persisted.setStatus(OrderStatus.CONFIRMED);
        entityManager.flush();

        assertThat(persisted.getVersion()).isEqualTo(1L);
    }

    @Test
    void creationAndUpdateTimestamps_arePopulatedByHibernate() {
        Order persisted = persist(TestData.order(null, "ORD-TS-1", OrderStatus.PENDING));

        assertThat(persisted.getOrderDate()).isNotNull();
        assertThat(persisted.getLastUpdated()).isNotNull();
    }
}
