# API Docs & Standards — SpringDoc, Pagination Contracts
**⬜ Not Started · plan: Phase 12, Tasks 52–53** — OpenAPI/Swagger, schema annotations, standardized paged responses

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** Your API contract already exists — SpringDoc just **reads it
off your code** (mappings, @Valid constraints, DTO types) and renders
OpenAPI JSON + Swagger UI. Annotations are for the parts code can't express:
descriptions, examples, which errors an endpoint returns. Task 53 is the same
discipline aimed at list endpoints: one standardized paged envelope, and
**every user-supplied sort/filter validated against a whitelist**.

```
code (controllers + DTOs + Bean Validation) ──SpringDoc──▶ /api-docs (OpenAPI 3 JSON)
                                                        └─▶ /swagger-ui.html (interactive)
list endpoint contract: content[] + page/size/totalElements/totalPages + next/prev links
```

**Five rules you must never get wrong:**
1. SpringDoc (not Springfox — dead, no Boot 3) generates from code; `@Operation`/`@Schema` add the human layer: descriptions, **examples**, error responses.
2. Document your error contract: every endpoint's @ApiResponse list includes the ErrorResponse shape for 400/404/422 — the [task 2](01-foundations/02-exceptions-validation.md) contract, published.
3. **Swagger in prod**: disabled, role-protected, or internal-only — an open Swagger UI is a machine-readable attack map.
4. **Whitelist sort fields.** `sortBy` from the user must match an allowed list — via Pageable it's a runtime error, via native SQL it's injection.
5. Cap page size (`@Max(100)`) — `?size=1000000` is a self-service DoS.

---

## 🔮 PREDICT FIRST

<details>
<summary><b>P1.</b> You add SpringDoc with zero annotations. What does Swagger UI already show for <code>POST /api/v1/orders</code> — and what's missing that makes it "documented" rather than merely "listed"?</summary>

Already there: the route, HTTP method, OrderRequest/OrderResponse schemas
with field types, and required-ness inferred from @NotBlank/@NotNull. Missing:
what the endpoint *means* (publishes a Kafka event!), realistic examples,
which error codes it returns and their body shape, auth requirements. The
free layer is structure; the annotation layer is intent — that's the split
that tells you where to spend effort.
</details>

<details>
<summary><b>P2.</b> <code>GET /orders?sortBy=customerName; DROP TABLE orders--</code> — with Spring Data's <code>Sort.by(sortBy)</code>, what happens? And why does the endpoint whitelist sort fields anyway?</summary>

No injection — Sort properties resolve through the JPA metamodel; a
nonexistent property throws PropertyReferenceException → 500 (ugly, not
dangerous). The whitelist exists because: (a) a clean 400 beats a leaky 500,
(b) it stops sorting by unindexed/sensitive fields (sort by `password` hash
timing, sort-by-huge-text-column table scans), and (c) the same code pattern
survives if anyone later drops to native SQL, where it WOULD be injection.
</details>

<details>
<summary><b>P3.</b> Security team scans prod and finds /swagger-ui.html open, listing an admin endpoint <code>POST /api/v1/kafka/admin/pause</code>. The endpoint itself requires ADMIN. What's the actual risk, and the three mitigation tiers?</summary>

The endpoint is protected, but the docs hand attackers a complete map:
every route, parameter shape, error behavior — reconnaissance for credential
stuffing, fuzzing, and social engineering ("call X with Y"). Same class of
leak as stack traces (task 2), just structured. Tiers: disable in prod
(`springdoc.api-docs.enabled: false`), require ADMIN for the swagger paths,
or expose only on the internal network. Public + unauthenticated is never
the answer.
</details>

---

## 📖 THE STORY

### 1. SpringDoc — generation plus intent

`springdoc-openapi-starter-webmvc-ui` → `/api-docs` (JSON) +
`/swagger-ui.html` (try-it-out UI). One `OpenAPI` bean supplies the global
info (title, version, contact) and — the piece people forget — the **security
scheme**: declaring `bearerAuth` (HTTP bearer, JWT) gives Swagger UI an
Authorize button so authenticated endpoints are testable from the browser.
(And permit `/swagger-ui/**`, `/api-docs/**` in the security config, per env.)

### 2. The annotation layer — where humans read

Controllers:

```java
@Tag(name = "Order Management", description = "CRUD operations for orders")
@Operation(summary = "Create a new order",
    description = "Creates an order with items. Publishes OrderCreated Kafka event.",
    responses = {
        @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = OrderResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation failed",
                     content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "Business rule violation", ...)
    })
```

Note what the good @Operation documents: **side effects** (the Kafka event)
and the **full error surface** — your GlobalExceptionHandler mapping made
public contract. DTOs get `@Schema(description, example, allowableValues)` per
field — examples are the highest-value annotation (Swagger's try-it-out
pre-fills them; clients copy them).

### 3. The paged-response standard

Spring's raw `Page<T>` JSON is unstable across versions and noisy — wrap it:

```java
public class PagedResponse<T> {
    private List<T> content;
    private int page, size;
    private long totalElements;
    private int totalPages;
    private boolean first, last;
    private String nextPage, previousPage;      // HATEOAS-lite links

    public static <T> PagedResponse<T> from(Page<T> page, String baseUrl) { ... }
}
```

One envelope for every list endpoint in the platform = clients write one
pagination component. next/previous links mean clients navigate without
computing page math.

### 4. The list endpoint, hardened

```java
@GetMapping
public ResponseEntity<PagedResponse<OrderSummaryDTO>> getOrders(
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,   // DoS cap
        @RequestParam(defaultValue = "orderDate") String sortBy,
        @RequestParam(defaultValue = "desc") String sortDir,
        @RequestParam(required = false) OrderStatus status,             // enum = self-validating
        @RequestParam(required = false) String customerEmail) {

    if (!List.of("orderDate", "totalAmount", "customerName", "status").contains(sortBy))
        throw new InvalidOrderException("Invalid sort field: " + sortBy);   // 400, named

    Pageable pageable = PageRequest.of(page, size,
        "asc".equalsIgnoreCase(sortDir) ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending());
    Specification<Order> spec = Specification.where(hasStatus(status))
                                             .and(hasCustomerEmail(customerEmail));
    ...
}
```

Everything here reuses earlier machinery: Specifications
([task 4](01-foundations/04-repositories-queries-pagination.md)) for composable optional filters, projections for
the summary shape, the 400-with-message error contract ([task 2](01-foundations/02-exceptions-validation.md)).
This task's contribution is the *contract discipline*: bounded inputs,
whitelisted sorts, one envelope.

---

## 🎯 RETRIEVAL GYM

**Tier 1 — must be automatic**

<details><summary><b>Q1.</b> SpringDoc vs Springfox — and what does SpringDoc generate with zero annotations?</summary>

Springfox is unmaintained and breaks on Boot 3; SpringDoc is the live OpenAPI
3 implementation. Free generation: routes, methods, request/response schemas
from DTO types, required flags from Bean Validation. Annotations add
descriptions, examples, error responses, auth — the intent layer.
</details>

<details><summary><b>Q2.</b> How do you make JWT-protected endpoints testable in Swagger UI?</summary>

Declare a SecurityScheme (type HTTP, scheme bearer, bearerFormat JWT) in the
OpenAPI bean and attach a SecurityRequirement — Swagger UI grows an Authorize
button; the pasted token rides every try-it-out request as the Authorization
header.
</details>

<details><summary><b>Q3.</b> Swagger in production — the three acceptable postures?</summary>

Disabled (springdoc.api-docs.enabled: false in the prod profile);
authenticated (swagger paths require a role); network-restricted (internal/
VPN only). Open Swagger = a machine-readable map of your attack surface, same
info-leak family as stack traces in responses.
</details>

<details><summary><b>Q4.</b> Harden a public list endpoint — the four input rules.</summary>

Bound page (@Min(0)) and size (@Max(100) — DoS cap); whitelist sortBy against
named fields → 400 otherwise; type filters as enums where possible
(self-validating); optional filters through Specifications, never
string-built queries.
</details>

<details><summary><b>Q5.</b> Why wrap Page<T> in a custom PagedResponse?</summary>

Spring's Page serialization is framework-internal (changes across versions,
carries noise) — wrapping gives a stable, documented envelope, uniform across
all endpoints, plus next/previous links so clients don't compute page math.
One pagination contract per platform.
</details>

**Tier 2 — depth**

<details><summary><b>Q6.</b> What belongs in @ApiResponse lists beyond 200/201?</summary>

The real error surface: 400 (validation, with ErrorResponse schema), 404,
409 (optimistic lock), 422 (business rules), 401/403 where secured — i.e.,
your GlobalExceptionHandler's mapping table, published. Clients code against
documented failures; undocumented ones become support tickets.
</details>

<details><summary><b>Q7.</b> Where does the OpenAPI JSON earn its keep beyond the UI?</summary>

It's the machine contract: client SDK generation (openapi-generator), contract
testing, gateway/route validation, breaking-change detection in CI (diff the
spec between versions), API catalogs. The UI is the demo; the JSON is the
product.
</details>

<details><summary><b>Q8.</b> Page vs Slice vs keyset for this endpoint family — how would you evolve it?</summary>

Numbered-page UI now → Page (needs totals). High-traffic/infinite scroll →
Slice (drop the COUNT). Deep archives → keyset cursor (phase 11) with the
cursor in nextPage links — the PagedResponse envelope can hide that migration
from clients, which is exactly why the envelope exists.
</details>

---

## 🃏 FLASHCARDS

```
SpringDoc endpoints	/api-docs (OpenAPI JSON) + /swagger-ui.html
Springfox status	Dead — no Spring Boot 3; SpringDoc always
Free vs annotated	Structure (routes, schemas, required) free; intent (desc, examples, errors) annotated
Highest-value @Schema attr	example — pre-fills try-it-out, clients copy it
JWT in Swagger	SecurityScheme bearer/JWT + SecurityRequirement → Authorize button
Swagger prod postures	Disabled / role-protected / internal-only — never open
sortBy handling	Whitelist → 400; Pageable is injection-safe but native SQL is not
size param	@Max(100) — unbounded size = self-service DoS
PagedResponse fields	content, page, size, totalElements, totalPages, first/last, next/prev links
Why the envelope	Stable contract, uniform across endpoints, hides pagination-strategy changes
Document side effects	@Operation description carries "publishes OrderCreated event"
```

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| This doc | Same idea as | Where |
|---|---|---|
| documented error surface | GlobalExceptionHandler mapping | `01-foundations/02` |
| Specifications-backed filters | composable optional predicates | `01-foundations/04` |
| envelope hides strategy | keyset migration path | `13-database-advanced` |
| open Swagger = info leak | stack traces / secrets hygiene | `01-foundations/02`, `01-foundations/07` |
| versioned contract | API versioning rules | `12-spring-advanced` |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)
