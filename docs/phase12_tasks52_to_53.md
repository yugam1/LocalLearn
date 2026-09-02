# Phase 12 — Documentation & API Management (Tasks 52–53)
**Estimated Time:** 2 hours | **Status:** ⬜ Not Started

## Task 52: OpenAPI / Swagger with SpringDoc

### Setup
```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.3.0</version>
</dependency>
```

```yaml
springdoc:
  api-docs:
    path: /api-docs
  swagger-ui:
    path: /swagger-ui.html
    operationsSorter: method
    tagsSorter: alpha
    display-request-duration: true
  show-actuator: false
```

UI: http://localhost:8080/swagger-ui.html
JSON: http://localhost:8080/api-docs

### OpenAPI Configuration
```java
@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI orderServiceApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Order Service API")
                        .description("Production-grade e-commerce order management service")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Yugam Prasad")
                                .email("yugam@example.com"))
                        .license(new License().name("MIT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
```

### Annotating Controllers
```java
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Order Management", description = "CRUD operations for orders")
public class OrderController {

    @Operation(
        summary = "Create a new order",
        description = "Creates an order with multiple items. Publishes OrderCreated Kafka event.",
        responses = {
            @ApiResponse(responseCode = "201", description = "Order created",
                content = @Content(schema = @Schema(implementation = OrderResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Business rule violation",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
        }
    )
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                description = "Order details with at least one item",
                required = true)
            @Valid @RequestBody OrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.createOrder(request));
    }

    @Operation(summary = "Get order by ID")
    @Parameter(name = "id", description = "Order ID", example = "1")
    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.getOrderById(id));
    }
}
```

### Annotating DTOs
```java
@Schema(description = "Request payload for creating an order")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderRequest {

    @Schema(description = "Customer full name", example = "John Doe", minLength = 2, maxLength = 100)
    @NotBlank
    private String customerName;

    @Schema(description = "Customer email address", example = "john@example.com")
    @NotBlank @Email
    private String customerEmail;

    @Schema(description = "List of items in the order (at least one required)")
    @Valid @NotEmpty
    private List<OrderItemRequest> items;
}

@Schema(description = "Response payload for an order")
@Data @Builder
public class OrderResponse {

    @Schema(description = "Unique order ID", example = "1")
    private Long id;

    @Schema(description = "Human-readable order number", example = "ORD-1729065000123")
    private String orderNumber;

    @Schema(description = "Current order status", example = "PENDING",
            allowableValues = {"PENDING", "CONFIRMED", "SHIPPED", "DELIVERED", "CANCELLED"})
    private OrderStatus status;

    @Schema(description = "Total order amount", example = "2999.99")
    private BigDecimal totalAmount;
}
```

### Security in Swagger
```java
// In SecurityConfig — permit swagger endpoints
.requestMatchers("/swagger-ui/**", "/api-docs/**", "/swagger-ui.html").permitAll()
```

---

## Task 53: Advanced Pagination, Filtering, Sorting

### Standardized Paginated Response
```java
@Data @Builder
public class PagedResponse<T> {
    private List<T> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean first;
    private boolean last;
    private String nextPage;
    private String previousPage;

    public static <T> PagedResponse<T> from(Page<T> page, String baseUrl) {
        PagedResponse<T> res = PagedResponse.<T>builder()
                .content(page.getContent())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .first(page.isFirst())
                .last(page.isLast())
                .build();
        if (page.hasNext()) res.setNextPage(baseUrl + "?page=" + (page.getNumber() + 1) + "&size=" + page.getSize());
        if (page.hasPrevious()) res.setPreviousPage(baseUrl + "?page=" + (page.getNumber() - 1) + "&size=" + page.getSize());
        return res;
    }
}
```

### Pageable from Request
```java
@GetMapping
public ResponseEntity<PagedResponse<OrderSummaryDTO>> getOrders(
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
        @RequestParam(defaultValue = "orderDate") String sortBy,
        @RequestParam(defaultValue = "desc") String sortDir,
        @RequestParam(required = false) OrderStatus status,
        @RequestParam(required = false) String customerEmail) {

    // Validate sort field against allowed list
    List<String> allowedSortFields = List.of("orderDate", "totalAmount", "customerName", "status");
    if (!allowedSortFields.contains(sortBy)) {
        throw new InvalidOrderException("Invalid sort field: " + sortBy);
    }

    Sort sort = sortDir.equalsIgnoreCase("asc")
            ? Sort.by(sortBy).ascending()
            : Sort.by(sortBy).descending();

    Pageable pageable = PageRequest.of(page, size, sort);

    Specification<Order> spec = Specification
            .where(OrderSpecification.hasStatus(status))
            .and(OrderSpecification.hasCustomerEmail(customerEmail));

    Page<OrderSummaryDTO> result = orderRepository.findSummariesByStatus(status, pageable);
    return ResponseEntity.ok(PagedResponse.from(result, "/api/v1/orders"));
}
```

### Response Example
```json
{
  "content": [
    {"id": 1, "orderNumber": "ORD-123", "status": "PENDING", "totalAmount": 2999.00}
  ],
  "page": 0,
  "size": 20,
  "totalElements": 150,
  "totalPages": 8,
  "first": true,
  "last": false,
  "nextPage": "/api/v1/orders?page=1&size=20",
  "previousPage": null
}
```

---

## Interview Q&A

**Q: SpringDoc vs Springfox?**
Springfox: old, not maintained, doesn't support Spring Boot 3. SpringDoc: actively maintained, supports Spring Boot 3, OpenAPI 3.0. Always use SpringDoc for new projects.

**Q: How do you secure Swagger in production?**
Option 1: Disable entirely — `springdoc.api-docs.enabled: false` in prod profile. Option 2: Protect with Spring Security — require ADMIN role. Option 3: Expose only on internal network/VPN. Never expose Swagger publicly without auth in production.

**Q: What is the difference between Page and Slice for API responses?**
Page: includes total count (requires COUNT query). Slice: no total count (faster). For cursor-based pagination APIs, Slice is more appropriate. For traditional page-number UIs, Page is needed.

**Q: How do you validate sort/filter parameters from user input?**
Validate sortBy field against whitelist of allowed column names. Never pass user input directly to JPQL/SQL (Specifications API handles this safely). Validate page/size bounds with @Min/@Max. Reject unknown filter fields with 400 error.

**Q: What goes in @Schema annotations?**
description, example, minimum, maximum, minLength, maxLength, pattern, allowableValues, required, deprecated. Makes Swagger UI much more useful — clients can understand expected values without reading code.
