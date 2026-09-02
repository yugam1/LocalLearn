# Phase 11 — Database Advanced Topics (Tasks 48–51)
**Estimated Time:** 4 hours | **Status:** ⬜ Not Started

## Task 48: Flyway Database Migration

### Why Flyway over ddl-auto?
| | ddl-auto update | Flyway |
|---|---|---|
| Version controlled | ❌ No | ✅ Yes |
| Rollback | ❌ Impossible | ✅ With undo scripts |
| Audit trail | ❌ No | ✅ flyway_schema_history table |
| Team coordination | ❌ Conflicts | ✅ Versioned scripts |
| Production safe | ❌ Dangerous | ✅ Yes |
| **Use in production** | **NEVER** | **Always** |

### Setup
```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
```

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true        # For existing DBs without flyway history
    validate-on-migrate: true        # Fail if checksum mismatch
    out-of-order: false              # Don't allow V3 before V2 is applied
  jpa:
    hibernate:
      ddl-auto: none                 # CRITICAL — Flyway owns schema, not Hibernate
```

### File Naming Convention
```
src/main/resources/db/migration/
  V1__create_orders_table.sql
  V2__create_order_items_table.sql
  V3__add_version_column_to_orders.sql
  V4__add_indexes_for_performance.sql
  V5__create_processed_events_table.sql
  V6__create_audit_logs_table.sql

Format: V{number}__{description}.sql  (double underscore!)
Repeatable: R__{description}.sql      (re-applied when checksum changes — for views/functions)
```

### Migration Scripts
```sql
-- V1__create_orders_table.sql
CREATE TABLE orders (
    id           BIGSERIAL PRIMARY KEY,
    version      BIGINT NOT NULL DEFAULT 0,
    order_number VARCHAR(50) NOT NULL,
    customer_name VARCHAR(100) NOT NULL,
    customer_email VARCHAR(100) NOT NULL,
    status       VARCHAR(20) NOT NULL,
    total_amount NUMERIC(10, 2) NOT NULL,
    order_date   TIMESTAMP NOT NULL,
    last_updated TIMESTAMP NOT NULL,
    CONSTRAINT uq_order_number UNIQUE (order_number)
);

-- V2__create_order_items_table.sql
CREATE TABLE order_items (
    id           BIGSERIAL PRIMARY KEY,
    order_id     BIGINT NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    quantity     INTEGER NOT NULL,
    unit_price   NUMERIC(10, 2) NOT NULL,
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE
);

-- V3__add_indexes_for_performance.sql
CREATE INDEX idx_orders_customer_email ON orders(customer_email);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_status_date ON orders(status, order_date DESC);
CREATE INDEX idx_order_items_order_id ON order_items(order_id);

-- V4__create_processed_events_table.sql
CREATE TABLE processed_events (
    id                    BIGSERIAL PRIMARY KEY,
    event_id              VARCHAR(100) NOT NULL,
    event_type            VARCHAR(50) NOT NULL,
    topic                 VARCHAR(100) NOT NULL,
    partition             INTEGER NOT NULL,
    "offset"              BIGINT NOT NULL,
    correlation_id        VARCHAR(100),
    processed_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    processing_duration_ms BIGINT,
    CONSTRAINT uq_processed_event_id UNIQUE (event_id)
);
CREATE INDEX idx_processed_events_at ON processed_events(processed_at);

-- V5__add_products_table.sql
CREATE TABLE products (
    id                 BIGSERIAL PRIMARY KEY,
    version            BIGINT NOT NULL DEFAULT 0,
    sku                VARCHAR(100) NOT NULL,
    name               VARCHAR(200) NOT NULL,
    price              NUMERIC(10, 2) NOT NULL,
    stock_quantity     INTEGER NOT NULL DEFAULT 0,
    reserved_quantity  INTEGER NOT NULL DEFAULT 0,
    active             BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_product_sku UNIQUE (sku)
);
```

### Flyway Commands
```bash
./mvnw flyway:info     # Show migration status
./mvnw flyway:migrate  # Apply pending migrations
./mvnw flyway:validate # Verify applied migrations match scripts
./mvnw flyway:repair   # Fix failed migration history
./mvnw flyway:clean    # Drop all objects (NEVER in production!)
```

---

## Task 49: Multi-Tenancy

### Approaches

| Approach | Isolation | Complexity | Cost |
|---|---|---|---|
| Separate DB | Highest | High | Highest |
| Separate Schema | High | Medium | Medium |
| Shared table (discriminator column) | Lowest | Low | Lowest |

### Schema-Based Multi-Tenancy with Hibernate
```java
// Identify current tenant from request
public class TenantContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    public static void setTenant(String tenant) { CURRENT.set(tenant); }
    public static String getTenant() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
}

// Extract tenant from JWT or header
@Component @Order(2)
public class TenantFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) req;
        String tenant = httpReq.getHeader("X-Tenant-ID");
        if (tenant == null) tenant = "default";
        TenantContext.setTenant(tenant);
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }
}

// Hibernate multi-tenant connection provider
@Component
public class SchemaMultiTenantConnectionProvider implements MultiTenantConnectionProvider<String> {
    private final DataSource dataSource;

    @Override
    public Connection getConnection(String tenantId) throws SQLException {
        Connection conn = dataSource.getConnection();
        conn.createStatement().execute("SET search_path TO " + tenantId + ", public");
        return conn;
    }

    @Override
    public void releaseConnection(String tenantId, Connection conn) throws SQLException {
        conn.createStatement().execute("SET search_path TO public");
        conn.close();
    }

    @Override
    public boolean isUnwrappableAs(Class unwrapType) { return false; }
    @Override
    public <T> T unwrap(Class<T> unwrapType) { return null; }
    @Override
    public Connection getAnyConnection() throws SQLException { return dataSource.getConnection(); }
    @Override
    public void releaseAnyConnection(Connection conn) throws SQLException { conn.close(); }
    @Override
    public boolean supportsAggressiveRelease() { return false; }
}

// Tenant identifier resolver
@Component
public class CurrentTenantIdentifierResolverImpl implements CurrentTenantIdentifierResolver<String> {
    @Override
    public String resolveCurrentTenantIdentifier() {
        String tenant = TenantContext.getTenant();
        return tenant != null ? tenant : "public";
    }
    @Override
    public boolean validateExistingCurrentSessions() { return true; }
}
```

```yaml
# application.yml
spring:
  jpa:
    properties:
      hibernate:
        multiTenancy: SCHEMA
        tenant_identifier_resolver: com.ecommerce.config.CurrentTenantIdentifierResolverImpl
        multi_tenant_connection_provider: com.ecommerce.config.SchemaMultiTenantConnectionProvider
```

---

## Task 50: Database Indexing & Query Optimization

### Index Types
```sql
-- B-Tree (default) — range queries, equality, ordering
CREATE INDEX idx_orders_email ON orders(customer_email);
CREATE INDEX idx_orders_date ON orders(order_date DESC);

-- Compound (left-prefix rule!)
CREATE INDEX idx_orders_email_status ON orders(customer_email, status);
-- Usable for: WHERE email = ?
--             WHERE email = ? AND status = ?
-- NOT usable: WHERE status = ?  (first column not included)

-- Partial — smaller, faster (only indexes matching rows)
CREATE INDEX idx_pending_orders_date ON orders(order_date)
    WHERE status = 'PENDING';

-- Covering — includes extra columns, avoids heap lookup
CREATE INDEX idx_orders_status_cover ON orders(status)
    INCLUDE (order_number, total_amount, order_date);

-- GIN — full-text search, JSONB, arrays
CREATE INDEX idx_orders_name_fts ON orders
    USING gin(to_tsvector('english', customer_name));

-- BRIN — huge tables with natural ordering (append-only)
CREATE INDEX idx_orders_date_brin ON orders
    USING brin(order_date);
```

### Query Analysis
```sql
-- EXPLAIN: shows query plan (estimated)
EXPLAIN SELECT * FROM orders WHERE customer_email = 'john@example.com';

-- EXPLAIN ANALYZE: actual execution (runs the query!)
EXPLAIN ANALYZE SELECT * FROM orders WHERE customer_email = 'john@example.com';

-- Look for:
-- Index Scan → good (uses index)
-- Seq Scan → bad (full table scan — no index or index not chosen)
-- Index Only Scan → best (covering index, no heap access)
-- Nested Loop → bad for large datasets (prefer Hash Join, Merge Join)

-- Check index usage
SELECT schemaname, tablename, indexname, idx_scan, idx_tup_read
FROM pg_stat_user_indexes
WHERE tablename = 'orders'
ORDER BY idx_scan DESC;

-- Find slow queries (pg_stat_statements extension)
SELECT query, mean_exec_time, calls, rows
FROM pg_stat_statements
ORDER BY mean_exec_time DESC
LIMIT 20;
```

### Common Optimization Techniques
```sql
-- 1. Use LIMIT for top-N queries
SELECT * FROM orders ORDER BY total_amount DESC LIMIT 10;

-- 2. Avoid functions on indexed columns in WHERE
-- ❌ BAD (can't use index on email)
SELECT * FROM orders WHERE LOWER(customer_email) = 'john@example.com';
-- ✅ GOOD (function index or store lowercase)
CREATE INDEX idx_orders_email_lower ON orders(LOWER(customer_email));

-- 3. Use EXISTS instead of IN for subqueries
-- ❌ Slower
SELECT * FROM orders WHERE id IN (SELECT order_id FROM order_items WHERE quantity > 10);
-- ✅ Faster (stops at first match)
SELECT * FROM orders o WHERE EXISTS (SELECT 1 FROM order_items oi WHERE oi.order_id = o.id AND oi.quantity > 10);

-- 4. Pagination — keyset better than OFFSET for deep pages
-- ❌ OFFSET gets slow at page 1000
SELECT * FROM orders ORDER BY id LIMIT 20 OFFSET 20000;
-- ✅ Keyset — always fast
SELECT * FROM orders WHERE id > :lastSeenId ORDER BY id LIMIT 20;
```

---

## Task 51: Read Replicas & Write-Through

### Read Replica Routing with Spring
```yaml
spring:
  datasource:
    primary:
      url: jdbc:postgresql://primary:5432/orderdb   # Write
    replica:
      url: jdbc:postgresql://replica:5432/orderdb   # Read
```

```java
@Configuration
public class DataSourceConfig {
    @Bean
    @Primary
    public DataSource dataSource() {
        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put("primary", primaryDataSource());
        targetDataSources.put("replica", replicaDataSource());

        AbstractRoutingDataSource routing = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                // Route based on transaction type
                return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                        ? "replica" : "primary";
            }
        };
        routing.setTargetDataSources(targetDataSources);
        routing.setDefaultTargetDataSource(primaryDataSource());
        return routing;
    }
}
// @Transactional(readOnly = true) → routes to replica automatically!
// @Transactional → routes to primary
```

---

## Interview Q&A

**Q: Flyway vs Liquibase vs ddl-auto?**
ddl-auto: NEVER in production — uncontrolled, no audit, can't rollback. Flyway: SQL files, version-controlled, simple, team-coordinated. Liquibase: XML/YAML/JSON changesets, database-agnostic, rollback built-in. Flyway preferred for SQL-native teams, Liquibase for cross-DB portability.

**Q: What is the compound index left-prefix rule?**
Index on (email, status) is usable only if the leftmost column (email) is in the WHERE clause. Can use for `WHERE email=?` or `WHERE email=? AND status=?`. Cannot use for `WHERE status=?` alone (email must be first). Determines column order when creating compound indexes.

**Q: Partial index — when to use?**
When only a subset of rows are queried. `WHERE status='PENDING'` on orders: if 5% of orders are pending, partial index covers only 5% of rows — much smaller, faster. Don't use for high-cardinality predicates that cover most rows.

**Q: Multi-tenancy approaches tradeoffs?**
Separate DB: highest isolation, highest cost. Separate schema: good isolation, moderate cost, one DB cluster. Shared table: lowest isolation (must be careful with SQL), lowest cost. Schema-based with Hibernate `search_path` is common balance.

**Q: Offset vs keyset pagination for deep pages?**
Offset: DB must count and skip N rows — gets slower with higher page numbers. Page 10,000 with OFFSET 200000 must scan 200,000 rows. Keyset: `WHERE id > lastSeenId` — always fast (index lookup). Trade-off: keyset can't jump to arbitrary page number.

**Q: What does EXPLAIN ANALYZE show?**
Actual execution plan with row counts, timing, and cost. Look for: `Seq Scan` (bad — no index), `Index Scan` (good), `Index Only Scan` (best — no heap access). `actual rows=` vs `estimated rows=` — large difference means stale statistics (run ANALYZE).
