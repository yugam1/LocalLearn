# Phase 1 — Task 2: Exception Handling + Validation
**Estimated Time:** 1 hour | **Status:** ✅ Completed

---

## 🎯 What You Learn
1. Global exception handling with `@RestControllerAdvice`
2. Custom exception class hierarchy
3. Bean Validation (JSR-380) — `@Valid`, `@NotBlank`, `@Email`, etc.
4. Validation groups for different scenarios
5. Custom validators for business rules
6. Standardized error response format
7. HTTP status code mapping by exception type
8. Field-level validation errors in API response

---

## 🧠 Core Concepts

### Why Global Exception Handling?

Without it, every controller method has try-catch blocks — repeated N times, inconsistent formats.

**Without (BAD):**
```java
@PostMapping
public ResponseEntity<?> createOrder(@RequestBody OrderRequest req) {
    try {
        return ResponseEntity.ok(orderService.createOrder(req));
    } catch (OrderNotFoundException e) {
        return ResponseEntity.status(404).body(null); // null body, no message
    } catch (Exception e) {
        return ResponseEntity.status(500).body(null);
    }
}
```

**With @RestControllerAdvice (GOOD):**
```java
@PostMapping
public ResponseEntity<OrderResponse> createOrder(@RequestBody OrderRequest req) {
    return ResponseEntity.ok(orderService.createOrder(req)); // CLEAN
}
// All exceptions caught centrally
```

### Exception Hierarchy

```
Throwable
└── Exception
    ├── RuntimeException (unchecked — no throws declaration needed)
    │   ├── BusinessException (your base)
    │   │   ├── OrderNotFoundException   → 404
    │   │   ├── InvalidOrderException    → 400
    │   │   └── InsufficientStockException → 422
    │   └── ConcurrentModificationException → 409
    └── IOException (checked — rarely used for business logic)
```

**Why extend RuntimeException for business exceptions?**
- No `throws` declaration needed on every method
- Can bubble up naturally to @ControllerAdvice
- Cleaner call sites

### Bean Validation (JSR-380)

Spring Boot includes Hibernate Validator (implementation of JSR-380). Annotations on DTO fields, triggered by `@Valid` on `@RequestBody`.

**Annotations cheat sheet:**

| Annotation | What it validates | Notes |
|---|---|---|
| `@NotNull` | Not null | Any type |
| `@NotEmpty` | Not null, not empty | String/Collection |
| `@NotBlank` | Not null, not empty, not whitespace | Strings only |
| `@Size(min,max)` | Length/size constraint | String/Collection |
| `@Min(value)` | Minimum numeric value | Numbers |
| `@Max(value)` | Maximum numeric value | Numbers |
| `@DecimalMin` | Minimum decimal value | BigDecimal |
| `@DecimalMax` | Maximum decimal value | BigDecimal |
| `@Digits(integer, fraction)` | Precision control | BigDecimal |
| `@Email` | Valid email format (RFC 5322) | String |
| `@Pattern(regexp)` | Regex match | String |
| `@Positive` | > 0 | Numbers |
| `@PositiveOrZero` | >= 0 | Numbers |
| `@Past` | Date in the past | Temporal |
| `@Future` | Date in the future | Temporal |

**Difference between @NotNull, @NotEmpty, @NotBlank:**
- `@NotNull`: value != null (allows "", " ")
- `@NotEmpty`: value != null AND size > 0 (allows " ")
- `@NotBlank`: value != null AND trimmed size > 0 (MOST STRICT for strings)

### Validation Flow

```
JSON body arrives
    ↓
Spring deserializes JSON → Java object
    ↓
@Valid present? Run Bean Validation
    ↓
Validation passes? → method executes
Validation fails? → MethodArgumentNotValidException thrown
    ↓
@RestControllerAdvice catches it
    ↓
Returns structured 400 error response
```

### HTTP Status Code Mapping

| Exception | HTTP Status | Reason |
|---|---|---|
| `OrderNotFoundException` | 404 Not Found | Resource does not exist |
| `InvalidOrderException` | 400 Bad Request | Input data error |
| `MethodArgumentNotValidException` | 400 Bad Request | Bean validation failed |
| `InsufficientStockException` | 422 Unprocessable Entity | Business rule violation |
| `ConcurrentModificationException` | 409 Conflict | Optimistic lock failure |
| `Exception` (catch-all) | 500 Internal Server Error | Unexpected error |

**400 vs 422:**
- 400: Syntax/structural problem (missing required field, wrong type)
- 422: Syntactically correct but business logic rejects it (valid quantity but out of stock)

### Standardized Error Response

```json
{
  "timestamp": "2024-10-16T10:30:00.123456",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed for request",
  "path": "/api/v1/orders",
  "errors": [
    {
      "field": "customerEmail",
      "rejectedValue": "not-valid",
      "message": "Customer email must be a valid email address"
    },
    {
      "field": "quantity",
      "rejectedValue": 0,
      "message": "Quantity must be at least 1"
    }
  ]
}
```

Never expose stack traces or internal class names to clients — security risk.

---

## 🛠️ Implementation

### Step 1: Add Validation Dependency

```xml
<!-- pom.xml — already included via spring-boot-starter-web, but explicit is fine -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

### Step 2: Exception Hierarchy

```java
// exception/BusinessException.java
public abstract class BusinessException extends RuntimeException {
    public BusinessException(String message) { super(message); }
    public BusinessException(String message, Throwable cause) { super(message, cause); }
}

// exception/OrderNotFoundException.java
public class OrderNotFoundException extends BusinessException {
    public OrderNotFoundException(Long id) {
        super("Order not found with ID: " + id);
    }
    public OrderNotFoundException(String orderNumber) {
        super("Order not found with order number: " + orderNumber);
    }
}

// exception/InvalidOrderException.java
public class InvalidOrderException extends BusinessException {
    public InvalidOrderException(String message) { super(message); }
}

// exception/InsufficientStockException.java
public class InsufficientStockException extends BusinessException {
    public InsufficientStockException(String productName, int requested, int available) {
        super(String.format(
            "Insufficient stock for '%s'. Requested: %d, Available: %d",
            productName, requested, available
        ));
    }
}

// exception/ConcurrentModificationException.java
public class ConcurrentModificationException extends BusinessException {
    public ConcurrentModificationException(String entityType, Long id) {
        super(String.format(
            "Concurrent modification detected for %s with ID: %d. Please refresh and retry.",
            entityType, id
        ));
    }
}
```

### Step 3: Error Response DTOs

```java
// dto/error/ErrorResponse.java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)  // Don't serialize null fields
public class ErrorResponse {
    private LocalDateTime timestamp;
    private int status;
    private String error;
    private String message;
    private String path;
    private List<ValidationError> errors; // Only for validation failures
}

// dto/error/ValidationError.java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ValidationError {
    private String field;
    private Object rejectedValue;
    private String message;
}
```

### Step 4: Global Exception Handler

```java
// exception/handler/GlobalExceptionHandler.java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleOrderNotFound(
            OrderNotFoundException ex, HttpServletRequest request) {
        log.error("Order not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            buildError(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI(), null)
        );
    }

    @ExceptionHandler(InvalidOrderException.class)
    public ResponseEntity<ErrorResponse> handleInvalidOrder(
            InvalidOrderException ex, HttpServletRequest request) {
        log.error("Invalid order: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI(), null)
        );
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientStock(
            InsufficientStockException ex, HttpServletRequest request) {
        log.error("Insufficient stock: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
            buildError(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request.getRequestURI(), null)
        );
    }

    @ExceptionHandler({
        OptimisticLockException.class,
        ConcurrentModificationException.class
    })
    public ResponseEntity<ErrorResponse> handleConflict(
            Exception ex, HttpServletRequest request) {
        log.error("Conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            buildError(HttpStatus.CONFLICT,
                "The record was modified by another user. Please refresh and retry.",
                request.getRequestURI(), null)
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        log.error("Validation failed: {}", ex.getMessage());

        List<ValidationError> fieldErrors = ex.getBindingResult()
                .getFieldErrors().stream()
                .map(fe -> ValidationError.builder()
                        .field(fe.getField())
                        .rejectedValue(fe.getRejectedValue())
                        .message(fe.getDefaultMessage())
                        .build())
                .collect(Collectors.toList());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            buildError(HttpStatus.BAD_REQUEST, "Validation failed for request",
                request.getRequestURI(), fieldErrors)
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAll(
            Exception ex, HttpServletRequest request) {
        log.error("Unexpected error", ex);  // Full stack trace in logs
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            buildError(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please contact support.",
                request.getRequestURI(), null)
        );
    }

    private ErrorResponse buildError(HttpStatus status, String message, String path,
                                      List<ValidationError> errors) {
        return ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(path)
                .errors(errors)
                .build();
    }
}
```

### Step 5: Validation on OrderRequest

```java
// dto/request/OrderRequest.java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderRequest {

    @NotBlank(message = "Customer name is required")
    @Size(min = 2, max = 100, message = "Customer name must be between 2 and 100 characters")
    private String customerName;

    @NotBlank(message = "Customer email is required")
    @Email(message = "Customer email must be a valid email address")
    private String customerEmail;

    @NotBlank(message = "Product name is required")
    @Size(min = 2, max = 200, message = "Product name must be between 2 and 200 characters")
    private String productName;

    @NotNull(message = "Quantity is required")
    @Min(value = 1, message = "Quantity must be at least 1")
    @Max(value = 1000, message = "Quantity cannot exceed 1000")
    private Integer quantity;

    @NotNull(message = "Unit price is required")
    @DecimalMin(value = "0.01", message = "Unit price must be at least 0.01")
    @DecimalMax(value = "999999.99", message = "Unit price cannot exceed 999,999.99")
    @Digits(integer = 6, fraction = 2, message = "Unit price: max 6 integer digits, 2 decimal places")
    private BigDecimal unitPrice;
}
```

### Step 6: Enable Validation in Controller

```java
// In OrderController — add @Valid to request body parameters
@PostMapping
public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody OrderRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(orderService.createOrder(request));
}

@PutMapping("/{id}")
public ResponseEntity<OrderResponse> updateOrder(
        @PathVariable Long id,
        @Valid @RequestBody OrderRequest request) {
    return ResponseEntity.ok(orderService.updateOrder(id, request));
}
```

### Step 7: Update Service to Use Custom Exceptions

```java
// In OrderServiceImpl
@Override
public OrderResponse getOrderById(Long id) {
    Order order = orderStore.get(id);
    if (order == null) throw new OrderNotFoundException(id);  // Custom, not RuntimeException
    return mapToResponse(order);
}
```

### Step 8: Custom Validator (Optional — shows mastery)

```java
// validation/ValidOrderQuantity.java
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = OrderQuantityValidator.class)
@Documented
public @interface ValidOrderQuantity {
    String message() default "Invalid order quantity";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

// validation/OrderQuantityValidator.java
public class OrderQuantityValidator implements ConstraintValidator<ValidOrderQuantity, Integer> {
    @Override
    public boolean isValid(Integer quantity, ConstraintValidatorContext ctx) {
        if (quantity == null) return true; // @NotNull handles null
        if (quantity < 1 || quantity > 1000) {
            ctx.disableDefaultConstraintViolation();
            ctx.buildConstraintViolationWithTemplate(
                "Quantity must be between 1 and 1000"
            ).addConstraintViolation();
            return false;
        }
        return true;
    }
}
```

---

## 🧪 Test Commands

```bash
# Validation failure (multiple fields)
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{"customerName":"","customerEmail":"invalid","productName":"A","quantity":0,"unitPrice":-1}'
# Expected: 400 with errors array listing all failures

# Valid request
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{"customerName":"John Doe","customerEmail":"john@example.com","productName":"Laptop","quantity":1,"unitPrice":999.99}'
# Expected: 201 Created

# Not found
curl http://localhost:8080/api/v1/orders/9999
# Expected: 404 with message "Order not found with ID: 9999"

# Invalid email only
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{"customerName":"John","customerEmail":"notanemail","productName":"Laptop","quantity":1,"unitPrice":999.99}'
# Expected: 400 with errors[0].field = "customerEmail"
```

---

## 🎯 Interview Q&A

**Q: What is @RestControllerAdvice and how does it differ from @ControllerAdvice?**
`@RestControllerAdvice` = `@ControllerAdvice` + `@ResponseBody`. Handles exceptions from ALL controllers. Returns JSON automatically. `@ControllerAdvice` alone needs `@ResponseBody` on handler methods or a view resolver.

**Q: Explain @NotNull vs @NotEmpty vs @NotBlank.**
- `@NotNull`: value != null (allows empty strings)
- `@NotEmpty`: not null AND size > 0 (allows whitespace-only strings)
- `@NotBlank`: not null AND trimmed length > 0 (most strict for strings — rejects " ")

**Q: How does @Valid work in Spring Boot?**
Placed before `@RequestBody`, it tells Spring to run Bean Validation after JSON deserialization. If any constraint fails, Spring throws `MethodArgumentNotValidException` before the method body executes. Global handler catches it.

**Q: What is the difference between 400 and 422?**
400 = bad request structure (missing field, wrong type). 422 = request is structurally valid but fails business rules (valid quantity integer but item out of stock).

**Q: Why extend RuntimeException for business exceptions?**
RuntimeException is unchecked — methods don't need `throws` declarations, keeping call sites clean. Business exceptions typically represent non-recoverable application-layer errors that should bubble up to the global handler.

**Q: Should you expose stack traces in error responses?**
Never in production. Stack traces reveal internal implementation (class names, library versions, architecture) — a security risk. Log the full trace internally; return only a generic message to clients.

**Q: How do you create a custom validator?**
1. Create annotation with `@Constraint(validatedBy = YourValidator.class)`
2. Implement `ConstraintValidator<YourAnnotation, FieldType>`
3. Override `isValid()` with custom logic
4. Apply annotation to DTO field

**Q: What happens if you put @Valid on a nested object field?**
The nested object is also validated. Without `@Valid` on the field, the nested object's constraints are ignored.

**Q: How do you validate a list of items inside a DTO?**
Add `@Valid` on the `List` field AND ensure items in the list have their own constraints:
```java
@Valid
@NotEmpty(message = "Order must contain at least one item")
private List<OrderItemRequest> items;
```
`@Valid` on the list triggers validation of each element.
