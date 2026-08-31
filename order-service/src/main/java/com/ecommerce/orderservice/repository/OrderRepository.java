package com.ecommerce.orderservice.repository;

import com.ecommerce.orderservice.dto.OrderSummaryDTO;
import com.ecommerce.orderservice.dto.projection.OrderSummary;
import com.ecommerce.orderservice.dto.projection.OrderWithItemCount;
import com.ecommerce.orderservice.model.Order;
import com.ecommerce.orderservice.model.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    Optional<Order> findByOrderNumber(String orderNumber);

    List<Order> findByCustomerEmail(String email);

    List<Order> findByStatus(OrderStatus status);

    boolean existsByOrderNumber(String orderNumber);

    long countByStatus(OrderStatus status);

    /** JOIN FETCH — loads order and items in a single query. See docs/phase1_task3.md & task6.md. */
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id = :id")
    Optional<Order> findByIdWithItems(@Param("id") Long id);

    /** Declarative equivalent of the JOIN FETCH above, via the @NamedEntityGraph on Order. */
    @EntityGraph(value = "Order.withItems")
    Optional<Order> findWithItemsByOrderNumber(String orderNumber);

    /** Step 2 of the two-query pagination pattern — fetch items for a page of IDs already resolved. */
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id IN :ids")
    List<Order> findByIdInWithItems(@Param("ids") List<Long> ids);

    /** Spring Data interface-projection idiom: bare findAllBy() + projection return type. */
    List<OrderSummary> findAllBy();

    /** Class/DTO projection via JPQL constructor expression — no items fetched at all. */
    @Query("SELECT new com.ecommerce.orderservice.dto.OrderSummaryDTO(o.id, o.orderNumber, o.totalAmount) FROM Order o")
    List<OrderSummaryDTO> findAllSummaryDtos();

    @Query("SELECT o.id AS id, o.orderNumber AS orderNumber, COUNT(oi) AS itemCount " +
           "FROM Order o LEFT JOIN o.items oi GROUP BY o.id, o.orderNumber")
    List<OrderWithItemCount> findOrderItemCounts();

    @Query("SELECT COALESCE(SUM(o.totalAmount), 0) FROM Order o WHERE o.orderDate >= :start AND o.orderDate < :end")
    BigDecimal sumTotalAmountBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT o.id FROM Order o ORDER BY o.orderDate DESC")
    Page<Long> findAllOrderIds(Pageable pageable);
}
