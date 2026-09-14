# Exception Handling + Validation
**✅ Completed · plan: Phase 1, Task 2** — @RestControllerAdvice, custom exceptions, Bean Validation

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** Exceptions are **part of your API contract**. Controllers stay
clean because every exception — yours or Spring's — funnels into **one**
`@RestControllerAdvice` that maps *exception type → HTTP status → one standard
error JSON*. Validation is declarative: constraints live on the DTO, `@Valid`
triggers them, and a failure is just another exception arriving at the same funnel.

```
JSON in ─▶ deserialize ─▶ @Valid? ──fail──▶ MethodArgumentNotValidException ─┐
                              │ pass                                          │
                              ▼                                               ▼
                        controller ─▶ service ──throws──▶ @RestControllerAdvice
                                                          type → status → ErrorResponse
```

**Five rules you must never get wrong:**
1. Business exceptions extend **RuntimeException** — unchecked bubbles freely to the advice, no `throws` litter.
2. `@Valid` on the `@RequestBody` or constraints are **silently ignored**. Same for nested objects/lists — `@Valid` doesn't cascade on its own.
3. `@NotNull` < `@NotEmpty` < `@NotBlank` (strictest for strings — rejects `" "`).
4. **400** = malformed request · **422** = well-formed but business says no · **409** = concurrent conflict.
5. **Never** leak stack traces or class names to clients — log the trace, return a generic message.

---

## 🔮 PREDICT FIRST

<details>
<summary><b>P1.</b> DTO has <code>@NotBlank private String customerName;</code>. Controller signature: <code>createOrder(@RequestBody OrderRequest req)</code> — no <code>@Valid</code>. Client sends <code>""</code>. What happens?</summary>

**Nothing.** The order is created with an empty name. Constraint annotations are
inert metadata until something triggers validation — `@Valid` (or `@Validated`)
is the trigger. This is the #1 silent validation bug.
</details>

<details>
<summary><b>P2.</b> <code>OrderRequest</code> contains <code>@NotEmpty private List&lt;OrderItemRequest&gt; items;</code> and each item has its own <code>@Min(1)</code>. Client sends one item with quantity 0. Rejected?</summary>

**No — accepted.** `@NotEmpty` checks only that the list has elements. Element
constraints run only if the list field itself carries `@Valid`:
`@Valid @NotEmpty private List<OrderItemRequest> items;`. Cascading is opt-in.
</details>

<details>
<summary><b>P3.</b> Two handlers in your advice match a thrown <code>OrderNotFoundException</code>: one for <code>OrderNotFoundException</code>, one for <code>Exception</code>. Which runs? And what if only a handler for <code>BusinessException</code> (its parent) exists?</summary>

**The most specific type wins** — Spring picks the closest match in the exception
hierarchy, not declaration order. With only the `BusinessException` handler, it
handles the subclass too. This is why one catch-all `Exception` handler at 500 is
safe: it only fires when nothing closer matched.
</details>

---

## 📖 THE STORY

### 1. One funnel, not N try-catches

Try-catch in every controller method means duplicated mapping, inconsistent JSON,
and a fat web layer. `@RestControllerAdvice` (= `@ControllerAdvice` +
`@ResponseBody`) intercepts exceptions from **all** controllers; each
`@ExceptionHandler` method owns one type → one status → one `ErrorResponse` shape.
Controllers shrink to one line per endpoint.

### 2. Design the hierarchy, then map it

```
RuntimeException
└── BusinessException (abstract base)
    ├── OrderNotFoundException        → 404
    ├── InvalidOrderException         → 400
    ├── InsufficientStockException    → 422
    └── ConcurrentModificationException → 409   (thrown on OptimisticLockException)
MethodArgumentNotValidException        → 400 (+ field errors array)
Exception (anything else)              → 500 (generic message, full trace in logs)
```

Unchecked base class is deliberate: business failures are not conditions the
*immediate* caller can fix, so forcing `throws` on every layer buys nothing —
let them bubble to the funnel. (Contrast with `@Transactional`'s rollback rule,
which keys off exactly this checked/unchecked split — [task 5](05-transactions-isolation-locking.md).)

**400 vs 422, the distinction interviewers probe:** 400 = the request itself is
defective (missing field, bad type, failed constraint). 422 = the request is
perfectly formed but reality refuses (quantity 5 is valid; stock is 3).

### 3. Bean Validation — constraints as data

Spring Boot ships Hibernate Validator (JSR-380). Constraints on DTO fields;
`@Valid` next to `@RequestBody` runs them **after deserialization, before your
method body**. Failure throws `MethodArgumentNotValidException` carrying *every*
failed field — so the client gets all problems in one round trip.

The string trio, strictest last:
- `@NotNull` — not null (allows `""`, `" "`)
- `@NotEmpty` — not null and size > 0 (allows `" "`)
- `@NotBlank` — not null and trimmed length > 0

Workhorses: `@Size(min,max)`, `@Min`/`@Max` (integers), `@DecimalMin`/`@DecimalMax`
+ `@Digits(integer, fraction)` (BigDecimal), `@Email`, `@Pattern`, `@Positive`,
`@Past`/`@Future`.

### 4. The error contract

```json
{
  "timestamp": "2024-10-16T10:30:00.123",
  "status": 400, "error": "Bad Request",
  "message": "Validation failed for request",
  "path": "/api/v1/orders",
  "errors": [
    { "field": "customerEmail", "rejectedValue": "not-valid",
      "message": "Customer email must be a valid email address" }
  ]
}
```

One shape for every failure; `errors[]` present only for validation
(`@JsonInclude(NON_NULL)`). Stack traces and internal class names stay in the
logs — exposing them hands attackers your library versions and architecture.

### 5. Custom validators — when annotations run out

Business rules ("quantity must be a multiple of pack size") get a custom
annotation + `ConstraintValidator`. Two conventions: return `true` for `null`
(let `@NotNull` own null-checking — single responsibility per constraint), and
use `ConstraintValidatorContext` to emit a specific message.

---

## 🛠 BUILD REFERENCE

<details>
<summary><b>Exception classes</b></summary>

```java
public abstract class BusinessException extends RuntimeException {
    public BusinessException(String message) { super(message); }
}
public class OrderNotFoundException extends BusinessException {
    public OrderNotFoundException(Long id) { super("Order not found with ID: " + id); }
}
public class InsufficientStockException extends BusinessException {
    public InsufficientStockException(String product, int requested, int available) {
        super("Insufficient stock for '%s'. Requested: %d, Available: %d"
              .formatted(product, requested, available));
    }
}
public class ConcurrentModificationException extends BusinessException {
    public ConcurrentModificationException(String entityType, Long id) {
        super("Concurrent modification detected for %s with ID: %d. Please refresh and retry."
              .formatted(entityType, id));
    }
}
```
</details>

<details>
<summary><b>ErrorResponse DTOs</b></summary>

```java
@Data @Builder @JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {
    private LocalDateTime timestamp;
    private int status;
    private String error, message, path;
    private List<ValidationError> errors;      // only on validation failures
}
@Data @Builder
public class ValidationError {
    private String field;
    private Object rejectedValue;
    private String message;
}
```
</details>

<details>
<summary><b>GlobalExceptionHandler</b></summary>

```java
@RestControllerAdvice @Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)          // → 404
    public ResponseEntity<ErrorResponse> notFound(OrderNotFoundException ex, HttpServletRequest req) {
        return respond(HttpStatus.NOT_FOUND, ex.getMessage(), req, null);
    }

    @ExceptionHandler(InsufficientStockException.class)      // → 422
    public ResponseEntity<ErrorResponse> stock(InsufficientStockException ex, HttpServletRequest req) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), req, null);
    }

    @ExceptionHandler({OptimisticLockException.class, ConcurrentModificationException.class}) // → 409
    public ResponseEntity<ErrorResponse> conflict(Exception ex, HttpServletRequest req) {
        return respond(HttpStatus.CONFLICT,
            "The record was modified by another user. Please refresh and retry.", req, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class) // → 400 + field errors
    public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException ex,
                                                    HttpServletRequest req) {
        List<ValidationError> fields = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> ValidationError.builder()
                    .field(fe.getField()).rejectedValue(fe.getRejectedValue())
                    .message(fe.getDefaultMessage()).build())
            .toList();
        return respond(HttpStatus.BAD_REQUEST, "Validation failed for request", req, fields);
    }

    @ExceptionHandler(Exception.class)                        // → 500, generic message
    public ResponseEntity<ErrorResponse> all(Exception ex, HttpServletRequest req) {
        log.error("Unexpected error", ex);                    // full trace ONLY in logs
        return respond(HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred. Please contact support.", req, null);
    }

    private ResponseEntity<ErrorResponse> respond(HttpStatus s, String msg,
            HttpServletRequest req, List<ValidationError> errors) {
        return ResponseEntity.status(s).body(ErrorResponse.builder()
            .timestamp(LocalDateTime.now()).status(s.value()).error(s.getReasonPhrase())
            .message(msg).path(req.getRequestURI()).errors(errors).build());
    }
}
```
</details>

<details>
<summary><b>Validated OrderRequest + controller wiring</b></summary>

```java
@Data @Builder
public class OrderRequest {
    @NotBlank(message = "Customer name is required")
    @Size(min = 2, max = 100)
    private String customerName;

    @NotBlank @Email(message = "Customer email must be a valid email address")
    private String customerEmail;

    @NotBlank @Size(min = 2, max = 200)
    private String productName;

    @NotNull @Min(1) @Max(1000)
    private Integer quantity;

    @NotNull @DecimalMin("0.01") @DecimalMax("999999.99")
    @Digits(integer = 6, fraction = 2)
    private BigDecimal unitPrice;
}
```

```java
@PostMapping
public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody OrderRequest request) { ... }
```
</details>

<details>
<summary><b>Custom validator pattern</b></summary>

```java
@Target(ElementType.FIELD) @Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = OrderQuantityValidator.class)
public @interface ValidOrderQuantity {
    String message() default "Invalid order quantity";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

public class OrderQuantityValidator implements ConstraintValidator<ValidOrderQuantity, Integer> {
    @Override
    public boolean isValid(Integer quantity, ConstraintValidatorContext ctx) {
        if (quantity == null) return true;          // @NotNull's job, not ours
        if (quantity < 1 || quantity > 1000) {
            ctx.disableDefaultConstraintViolation();
            ctx.buildConstraintViolationWithTemplate("Quantity must be between 1 and 1000")
               .addConstraintViolation();
            return false;
        }
        return true;
    }
}
```
</details>

<details>
<summary><b>curl checks</b></summary>

```bash
# multi-field failure → 400 with errors[] listing ALL violations
curl -X POST localhost:8080/api/v1/orders -H "Content-Type: application/json" \
  -d '{"customerName":"","customerEmail":"invalid","productName":"A","quantity":0,"unitPrice":-1}'

# missing id → 404 with structured body
curl localhost:8080/api/v1/orders/9999
```
</details>

---

## 🎯 RETRIEVAL GYM

**Tier 1 — must be automatic**

<details><summary><b>Q1.</b> How does @Valid actually work — what runs, when, and what's thrown?</summary>

After JSON deserialization, before the method body, Spring hands the object to
Hibernate Validator. Any constraint failure throws
`MethodArgumentNotValidException` (carrying all field errors); the advice maps it
to 400 with an `errors[]` array. Without `@Valid`, constraints are dead metadata.
</details>

<details><summary><b>Q2.</b> @NotNull vs @NotEmpty vs @NotBlank — and which for a required string field?</summary>

@NotNull: non-null only. @NotEmpty: non-null and size > 0. @NotBlank: non-null and
trimmed length > 0 — rejects `" "`, so it's the one for required strings.
</details>

<details><summary><b>Q3.</b> 400 vs 422 vs 409 — one example each from the order API.</summary>

400 — email fails `@Email` (defective request). 422 — quantity 5 is valid but
stock is 3 (business rule). 409 — optimistic-lock failure on concurrent update
("refresh and retry").
</details>

<details><summary><b>Q4.</b> Why do business exceptions extend RuntimeException?</summary>

Unchecked = no `throws` on every signature; the immediate caller usually can't
recover anyway, so the exception should bubble untouched to the central advice.
Side effect to know: unchecked is also what triggers @Transactional rollback by
default.
</details>

<details><summary><b>Q5.</b> How do you validate each element of a List field in a DTO?</summary>

`@Valid @NotEmpty private List<OrderItemRequest> items;` — `@NotEmpty` guards the
list itself, `@Valid` on the field cascades validation into every element.
Without the field-level `@Valid`, element constraints never run.
</details>

**Tier 2 — depth**

<details><summary><b>Q6.</b> Two handlers could match one exception — how does Spring choose?</summary>

Most specific exception type in the hierarchy wins, regardless of method order.
That's what makes a catch-all `Exception` → 500 handler safe as a backstop.
</details>

<details><summary><b>Q7.</b> Why return `true` for null in a custom validator?</summary>

Single responsibility per constraint: null-ness belongs to `@NotNull`. If the
custom validator also rejected null, you couldn't compose it on an optional field.
</details>

<details><summary><b>Q8.</b> What's the security argument for a boring 500 body?</summary>

Stack traces reveal class names, framework/library versions, file paths, query
fragments — reconnaissance for attackers. Log the trace with a correlation ID
([task 7](07-logging-mdc-correlation-ids.md)); return only "unexpected error + support reference."
</details>

---

## 🃏 FLASHCARDS

```
@Valid missing on @RequestBody	Constraints silently ignored — validation never runs
@RestControllerAdvice = ?	@ControllerAdvice + @ResponseBody, catches from ALL controllers
Validation failure exception	MethodArgumentNotValidException → 400 + all field errors
Strictest not-X for strings	@NotBlank (rejects null, "", " ")
Cascade validation into a nested object/list	@Valid on the FIELD (not automatic)
422 means	Well-formed request, business rule rejects (stock, state)
Handler selection with multiple matches	Most specific exception type wins
Custom validator null convention	return true — @NotNull owns null
Why unchecked business exceptions	No throws litter; bubbles to advice; also = default rollback trigger
Stack trace in response?	Never — log internally, generic message + correlation id out
```

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| This doc | Same idea as | Where |
|---|---|---|
| unchecked → rollback default | @Transactional rollback rules | `01-foundations/05` |
| ConcurrentModificationException → 409 | @Version optimistic locking | `01-foundations/05` |
| one funnel for cross-cutting concern | AOP aspects / MDC filter | `11-aop-proxies`, `01-foundations/07` |
| declarative constraints | declarative tx/caching annotations | `01-foundations/05`, `08-caching` |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)
