# Phase 1 — Task 5: Transaction Management
**Estimated Time:** 1.5 hours | **Status:** ✅ Completed

---

## ACID Properties
- **Atomicity** — All-or-nothing (bank transfer: both debit+credit commit or both rollback)
- **Consistency** — Data integrity maintained after every transaction
- **Isolation** — Concurrent transactions don't interfere with each other
- **Durability** — Committed data survives system crashes

---

## Transaction Propagation

| Propagation | Behavior | Use Case |
|---|---|---|
| `REQUIRED` (default) | Join existing tx, or create new | Most operations |
| `REQUIRES_NEW` | Always new tx, suspends existing | Audit logs — commit even if caller fails |
| `MANDATORY` | Must have existing tx; error if none | Internal-only methods |
| `NESTED` | Savepoint in existing tx | Partial rollback within batch |
| `SUPPORTS` | Use tx if exists, else non-tx | Optional tx reads |
| `NOT_SUPPORTED` | Always non-tx (suspend existing) | Long-running non-DB ops |
| `NEVER` | Must NOT have tx; error if exists | Diagnostics/monitoring |

### REQUIRES_NEW — Key Interview Scenario
```java
// Audit log must commit even if order creation rolls back
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void logOrderCreated(Long orderId, String userId) {
    // Runs in SEPARATE transaction
    // Commits independently — even if caller's tx rolls back
    auditRepo.save(new AuditLog(orderId, "CREATED", userId));
}

@Transactional
public OrderResponse createOrder(OrderRequest req) {
    Order order = orderRepo.save(buildOrder(req));
    auditService.logOrderCreated(order.getId(), req.getCustomerEmail()); // New tx
    validateComplexRule(order); // If this throws, order tx rolls back, BUT audit is committed
    return mapToResponse(order);
}
```

### MANDATORY — Protect Internal Methods
```java
// This must NEVER be called without an active transaction
@Transactional(propagation = Propagation.MANDATORY)
private void reserveInventoryItems(List<OrderItem> items) {
    // If no active tx exists → TransactionRequiredException
    items.forEach(this::reserveItem);
}
```

---

## Isolation Levels

### Concurrency Anomalies

**Dirty Read:** T2 reads T1's uncommitted data → T1 rolls back → T2 used invalid data.
**Non-Repeatable Read:** T2 reads same row twice → T1 updates row and commits between reads → different results.
**Phantom Read:** T2 queries a range twice → T1 inserts row in range between queries → different row count.

### Levels vs Problems

| Level | Dirty Read | Non-Repeatable | Phantom | Notes |
|---|---|---|---|---|
| `READ_UNCOMMITTED` | ❌ Possible | ❌ Possible | ❌ Possible | Almost never used |
| `READ_COMMITTED` | ✅ Safe | ❌ Possible | ❌ Possible | **PostgreSQL default** |
| `REPEATABLE_READ` | ✅ Safe | ✅ Safe | ❌ Possible | Financial calculations |
| `SERIALIZABLE` | ✅ Safe | ✅ Safe | ✅ Safe | Critical sections only |

```java
// Financial calculation — must be consistent throughout
@Transactional(isolation = Isolation.REPEATABLE_READ)
public BigDecimal calculateRevenue(LocalDate date) {
    BigDecimal morning = orderRepo.sumByDate(date);
    doHeavyCalculation();
    BigDecimal final = orderRepo.sumByDate(date); // == morning, guaranteed
    return final;
}

// Last-item allocation — prevent race conditions completely
@Transactional(isolation = Isolation.SERIALIZABLE)
public void allocateLastItem(String sku, Long orderId) {
    Product p = productRepo.findBySku(sku).get();
    if (p.getStock() > 0) p.decrementStock(); // Only ONE tx can do this at a time
}
```

---

## Optimistic Locking (@Version)

```java
@Entity
public class Order {
    @Version
    private Long version; // Auto-incremented on every UPDATE
}
```

**What Hibernate generates on save:**
```sql
UPDATE orders SET ..., version = 2 WHERE id = 1 AND version = 1;
-- 0 rows updated? → another tx already updated → OptimisticLockException
```

**Handle gracefully:**
```java
@Transactional
public OrderResponse updateOrder(Long id, OrderRequest req) {
    try {
        Order order = orderRepo.findByIdWithItems(id).orElseThrow(() -> new OrderNotFoundException(id));
        // ... update logic ...
        return mapToResponse(orderRepo.save(order));
    } catch (OptimisticLockException e) {
        throw new ConcurrentModificationException("Order", id);
        // Returns 409 Conflict to client: "Please refresh and retry"
    }
}
```

**Optimistic use cases:** Most concurrent updates (user profile, order updates). Lightweight — no DB locks. Works across HTTP requests.

---

## Pessimistic Locking (@Lock — SELECT FOR UPDATE)

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Product p WHERE p.id = :id")
Optional<Product> findByIdWithLock(@Param("id") Long id);
// SQL: SELECT ... FROM products WHERE id = ? FOR UPDATE
// Row locked until tx commits — ALL others WAIT or timeout
```

**Lock types:**

| Type | Behavior |
|---|---|
| `PESSIMISTIC_READ` | Multiple readers OK, no writers |
| `PESSIMISTIC_WRITE` | Exclusive — one tx at a time |
| `PESSIMISTIC_FORCE_INCREMENT` | Exclusive + increments @Version |

**Pessimistic use cases:** High-contention (last seat, inventory allocation), short transactions, when optimistic conflicts are frequent.

---

## Rollback Rules

**Default:** RuntimeException (unchecked) → rollback. Checked Exception → NO rollback.

```java
@Transactional(
    rollbackFor = {Exception.class},           // Rollback for ALL exceptions (including checked)
    noRollbackFor = {WarningException.class}   // But not for this one
)
public void processOrder() throws IOException { ... }

// Manual rollback without throwing
@Transactional
public void processWithManualRollback() {
    try {
        doWork();
    } catch (SomeException e) {
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        log.error("Marked for rollback", e);
    }
}
```

---

## Read-Only Transactions

```java
@Service
@Transactional(readOnly = true) // Default for ALL methods
public class OrderServiceImpl implements OrderService {

    // Inherits readOnly = true — Hibernate skips dirty checking, no flush
    public List<OrderResponse> getAllOrders() {
        return orderRepo.findAll().stream().map(this::mapToResponse).toList();
    }

    @Transactional // Override for write operations
    public OrderResponse createOrder(OrderRequest req) { ... }

    @Transactional
    public void deleteOrder(Long id) { ... }
}
```

**Benefits of readOnly = true:**
1. Hibernate skips dirty checking (no snapshot comparison on flush)
2. No flush needed → faster
3. Some databases optimise read-only transactions
4. Can route to read replicas
5. Documents intent — reviewer knows this doesn't mutate

---

## Transaction Best Practices

**Keep transactions short:**
```java
// ❌ WRONG — HTTP call inside transaction holds DB connection!
@Transactional
public OrderResponse createOrder(OrderRequest req) {
    Order order = orderRepo.save(buildOrder(req));
    emailService.sendConfirmationEmail(order);  // HTTP call — could take seconds!
    return mapToResponse(order);
}

// ✅ CORRECT — async for side effects
@Transactional
public OrderResponse createOrder(OrderRequest req) {
    Order order = orderRepo.save(buildOrder(req));
    kafkaProducer.publishOrderCreated(buildEvent(order)); // Async — non-blocking
    return mapToResponse(order);
}
```

**Why long transactions are dangerous:**
- Hold DB connection (pool exhaustion)
- Hold row-level locks (block other transactions)
- More expensive rollback
- Higher deadlock risk

---

## Key Interview Q&A

**Q: REQUIRED vs REQUIRES_NEW?**
REQUIRED joins existing or creates new — all one transaction, all-or-nothing. REQUIRES_NEW creates separate transaction, suspends caller — commits independently. Use REQUIRES_NEW for audit logs that must persist even if main operation fails.

**Q: Does REQUIRES_NEW child tx rollback when parent fails?**
No. REQUIRES_NEW creates a completely separate transaction. It has already committed before the parent rolled back. This is why it's used for audit logs — you want the log even if the operation failed.

**Q: When use SERIALIZABLE isolation?**
Only for critical operations where correctness > performance: last-item allocation, airline seat reservation, financial balances. Severely impacts concurrency — every other session must wait.

**Q: Optimistic vs pessimistic locking?**
Optimistic (@Version): no DB lock, uses version column, detects conflict at commit → OptimisticLockException. Great for low-contention, works across HTTP requests. Pessimistic (@Lock FOR UPDATE): DB-level lock, blocks all others → they wait. Great for high-contention, short transactions.

**Q: What happens if you throw a checked exception in @Transactional?**
By default, NO rollback. Only RuntimeException causes rollback. Use `@Transactional(rollbackFor = Exception.class)` to include checked exceptions.

**Q: Why readOnly = true at class level?**
All methods default to read-only — Hibernate skips dirty checking, no flush overhead. Individual write methods override with `@Transactional`. Pattern: set class-level readOnly, selectively override for mutations.

**Q: What's a phantom read? When does it matter?**
T1 queries "orders WHERE status=PENDING" → gets 5. T2 inserts 1 PENDING order and commits. T1 queries again → gets 6 (phantom!). Only preventable with SERIALIZABLE isolation. READ_COMMITTED and REPEATABLE_READ don't prevent phantoms.
