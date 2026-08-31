# Phase 1 — Task 4: Repository Layer + Custom Queries + Specifications
**Estimated Time:** 1 hour | **Status:** ✅ Completed

> Full content is in the `phase1_task4` artifact in this chat session.
> Key topics: Specifications API, Page vs Slice, DTO Projections, Paginated JOIN FETCH (two-query), Entity Graphs, aggregate queries, native SQL.

## Quick Reference

### Enable Specifications
```java
public interface OrderRepository extends JpaRepository<Order, Long>,
        JpaSpecificationExecutor<Order> { ... }
```

### Specification Pattern
```java
Specification<Order> spec = Specification
    .where(hasCustomerEmail(email))
    .and(hasStatus(status))
    .and(hasTotalAmountBetween(min, max));

orderRepository.findAll(spec, pageable);
```

Each spec is null-safe:
```java
public static Specification<Order> hasStatus(OrderStatus status) {
    return (root, query, cb) -> status == null
        ? cb.conjunction()  // no filter
        : cb.equal(root.get("status"), status);
}
```

### Paginated JOIN FETCH (Two-Query)
```java
// Step 1: DB-level pagination (LIMIT/OFFSET)
Page<Long> ids = repo.findAllOrderIds(pageable);
// Step 2: JOIN FETCH for those IDs
List<Order> orders = repo.findByIdInWithItems(ids.getContent());
```

### Projection Types
```java
// Interface projection — Spring generates impl
public interface OrderSummary {
    Long getId(); String getOrderNumber(); BigDecimal getTotalAmount();
}
List<OrderSummary> summaries = repo.findAllProjectedBy();

// Class projection — JPQL constructor expression
@Query("SELECT new com.ecommerce.dto.OrderSummaryDTO(o.id, o.orderNumber) FROM Order o")
List<OrderSummaryDTO> findAllSummaries();
```

## Interview Q&A
- **Specifications API?** Type-safe, composable query predicates via JPA Criteria. null-safe optional filters. Enable with JpaSpecificationExecutor.
- **Page vs Slice?** Page = COUNT query = knows total pages. Slice = no COUNT = faster, only knows if next exists. Slice for infinite scroll.
- **Paginate with JOIN FETCH?** Never directly — HHH90003004 warning (in-memory pagination). Always two-query: page IDs, then JOIN FETCH by IDs.
- **@EntityGraph vs JOIN FETCH?** Same SQL output. EntityGraph is declarative (reusable per method). JOIN FETCH is embedded in @Query string.
