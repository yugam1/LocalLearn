# Phase 3 — Testing (Tasks 16–21)
**Estimated Time:** 6 hours | **Status:** ⬜ Not Started

---

## Task 16: Unit Testing with Mockito (1hr)

### What You Learn
- JUnit 5 (Jupiter) — @Test, @BeforeEach, @AfterEach, @ParameterizedTest
- Mockito — @Mock, @InjectMocks, @Spy, @Captor
- Stubbing — when/thenReturn, doThrow, doAnswer
- Verification — verify(), verifyNoMoreInteractions()
- Argument captors — capture and assert on method arguments
- Testing service layer in isolation (no Spring context)

### Key Patterns

#### Service Unit Test
```java
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private KafkaProducerService kafkaProducerService;

    @Mock
    private InventoryService inventoryService;

    @InjectMocks
    private OrderServiceImpl orderService;

    @Captor
    private ArgumentCaptor<Order> orderCaptor;

    @Test
    @DisplayName("should create order and publish Kafka event")
    void createOrder_success() {
        // Arrange
        OrderRequest request = buildOrderRequest();
        Order savedOrder = buildSavedOrder(1L);
        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);
        doNothing().when(kafkaProducerService).publishOrderCreated(any());
        doNothing().when(inventoryService).reserveStock(anyString(), anyInt(), anyString());

        // Act
        OrderResponse response = orderService.createOrder(request);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.getOrderNumber()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(OrderStatus.PENDING);

        // Verify interactions
        verify(orderRepository).save(orderCaptor.capture());
        Order capturedOrder = orderCaptor.getValue();
        assertThat(capturedOrder.getCustomerEmail()).isEqualTo(request.getCustomerEmail());

        verify(kafkaProducerService).publishOrderCreated(any(OrderCreatedEvent.class));
        verify(inventoryService, times(request.getItems().size()))
                .reserveStock(anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("should throw OrderNotFoundException when order not found")
    void getOrderById_notFound_throws() {
        // Arrange
        when(orderRepository.findByIdWithItems(99L)).thenReturn(Optional.empty());

        // Act + Assert
        assertThatThrownBy(() -> orderService.getOrderById(99L))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining("99");

        verifyNoInteractions(kafkaProducerService);
    }

    @Test
    @DisplayName("should rollback order when inventory reservation fails")
    void createOrder_inventoryFails_rollback() {
        // Arrange
        OrderRequest request = buildOrderRequest();
        Order savedOrder = buildSavedOrder(1L);
        when(orderRepository.save(any())).thenReturn(savedOrder);
        doThrow(new InsufficientStockException("LAPTOP", 5, 2))
                .when(inventoryService).reserveStock(anyString(), anyInt(), anyString());

        // Act + Assert
        assertThatThrownBy(() -> orderService.createOrder(request))
                .isInstanceOf(InsufficientStockException.class);

        // No Kafka event published
        verifyNoInteractions(kafkaProducerService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "a"})
    void createOrder_invalidCustomerName_fails(String invalidName) {
        OrderRequest request = OrderRequest.builder().customerName(invalidName).build();
        assertThatThrownBy(() -> orderService.createOrder(request));
    }
}
```

#### Mocking Patterns
```java
// stubbing
when(mock.method(arg)).thenReturn(value);
when(mock.method()).thenThrow(new Exception("..."));
when(mock.method(any())).thenAnswer(inv -> inv.getArgument(0));  // return first arg

// void methods
doNothing().when(mock).voidMethod();
doThrow(new Exception()).when(mock).voidMethod();

// Argument matchers
when(repo.findById(anyLong())).thenReturn(Optional.of(order));
when(repo.findByEmail(eq("john@x.com"))).thenReturn(Optional.of(order));
when(repo.findByStatus(any(OrderStatus.class))).thenReturn(List.of());

// Verification
verify(mock, times(1)).method(arg);
verify(mock, never()).method();
verify(mock, atLeastOnce()).method();
verifyNoMoreInteractions(mock1, mock2);

// Spy (partial mock)
@Spy
private OrderServiceImpl spy = new OrderServiceImpl(...);
doReturn(mockValue).when(spy).privateHelperMethod();
```

---

## Task 17: Integration Testing — @SpringBootTest (1hr)

### What You Learn
- @SpringBootTest — full application context
- TestRestTemplate — HTTP client for integration tests
- @AutoConfigureTestDatabase — H2 for tests
- @Transactional on tests — automatic rollback
- @TestPropertySource — test-specific properties
- TestContainers — real PostgreSQL and Kafka

### Key Patterns

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class OrderIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
    }

    @Test
    void createOrder_fullFlow_success() {
        // Arrange
        OrderRequest request = OrderRequest.builder()
                .customerName("Integration Test User")
                .customerEmail("integration@test.com")
                .items(List.of(OrderItemRequest.builder()
                        .productName("TEST-PRODUCT")
                        .quantity(1)
                        .unitPrice(new BigDecimal("99.99"))
                        .build()))
                .build();

        // Act
        ResponseEntity<OrderResponse> response = restTemplate.postForEntity(
                "/api/v1/orders", request, OrderResponse.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getOrderNumber()).startsWith("ORD-");
        assertThat(response.getBody().getTotalAmount()).isEqualByComparingTo("99.99");

        // Verify in DB
        Optional<Order> savedOrder = orderRepository.findById(response.getBody().getId());
        assertThat(savedOrder).isPresent();
        assertThat(savedOrder.get().getCustomerEmail()).isEqualTo("integration@test.com");
    }

    @Test
    void getOrder_notFound_returns404() {
        ResponseEntity<ErrorResponse> response = restTemplate.getForEntity(
                "/api/v1/orders/99999", ErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
```

### TestContainers — Real Database
```java
@SpringBootTest
@Testcontainers
class OrderRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void findByOrderNumber_exists() {
        Order saved = orderRepository.save(buildOrder());
        Optional<Order> found = orderRepository.findByOrderNumber(saved.getOrderNumber());
        assertThat(found).isPresent();
    }
}
```

### application-test.yml
```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: create-drop
  kafka:
    bootstrap-servers: localhost:9092  # Override in TestContainers tests
  main:
    allow-bean-definition-overriding: true

logging:
  level:
    com.ecommerce: DEBUG
```

---

## Task 18: Repository Testing — @DataJpaTest (1hr)

### What You Learn
- @DataJpaTest — lightweight JPA slice (no full Spring context)
- Only JPA components loaded: entities, repositories, JPA config
- Uses embedded H2 by default
- @Transactional — test isolation (auto-rollback)
- TestEntityManager — low-level JPA ops in tests
- Custom query method testing

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void findByCustomerEmail_returnsOrders() {
        // Arrange — use TestEntityManager to persist test data
        Order order = entityManager.persistAndFlush(Order.builder()
                .orderNumber("ORD-TEST-1")
                .customerEmail("test@example.com")
                .customerName("Test User")
                .status(OrderStatus.PENDING)
                .totalAmount(new BigDecimal("99.99"))
                .build());

        // Act
        List<Order> found = orderRepository.findByCustomerEmail("test@example.com");

        // Assert
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getOrderNumber()).isEqualTo("ORD-TEST-1");
    }

    @Test
    void findByIdWithItems_returnsOrderWithItems() {
        Order order = entityManager.persist(buildOrderWithItems(2));
        entityManager.flush();
        entityManager.clear();  // Clear cache to force DB read

        Optional<Order> found = orderRepository.findByIdWithItems(order.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getItems()).hasSize(2);
    }

    @Test
    void countByStatus_returnsCorrectCount() {
        entityManager.persistAndFlush(buildOrder(OrderStatus.PENDING));
        entityManager.persistAndFlush(buildOrder(OrderStatus.PENDING));
        entityManager.persistAndFlush(buildOrder(OrderStatus.CONFIRMED));

        assertThat(orderRepository.countByStatus(OrderStatus.PENDING)).isEqualTo(2);
        assertThat(orderRepository.countByStatus(OrderStatus.CONFIRMED)).isEqualTo(1);
    }

    @Test
    void findByStatus_withSpecification() {
        // Test Specifications API
        entityManager.persistAndFlush(buildOrder(OrderStatus.PENDING, "alice@x.com", new BigDecimal("500")));
        entityManager.persistAndFlush(buildOrder(OrderStatus.PENDING, "bob@x.com", new BigDecimal("2000")));

        Specification<Order> spec = OrderSpecification.hasStatus(OrderStatus.PENDING)
                .and(OrderSpecification.hasTotalAmountGreaterThan(new BigDecimal("1000")));

        List<Order> found = orderRepository.findAll(spec);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getCustomerEmail()).isEqualTo("bob@x.com");
    }
}
```

---

## Task 19: Controller Testing — @WebMvcTest + MockMvc (1hr)

### What You Learn
- @WebMvcTest — only web layer, no service/repo
- MockMvc — simulate HTTP requests without network
- @MockBean — mock service dependencies
- Testing validation errors
- Testing error responses
- Security configuration in tests

```java
@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrderService orderService;

    @Test
    void createOrder_validRequest_returns201() throws Exception {
        // Arrange
        OrderRequest request = buildValidOrderRequest();
        OrderResponse response = buildOrderResponse(1L);
        when(orderService.createOrder(any(OrderRequest.class))).thenReturn(response);

        // Act + Assert
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("X-Correlation-ID", "TEST-001"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.orderNumber").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(header().string("X-Correlation-ID", "TEST-001"));
    }

    @Test
    void createOrder_invalidRequest_returns400WithErrors() throws Exception {
        // Empty request body
        OrderRequest invalidRequest = OrderRequest.builder()
                .customerName("")  // blank
                .customerEmail("not-an-email")  // invalid
                .items(List.of())  // empty
                .build();

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[*].field",
                        containsInAnyOrder("customerName", "customerEmail", "items")));
    }

    @Test
    void getOrder_notFound_returns404() throws Exception {
        when(orderService.getOrderById(99L))
                .thenThrow(new OrderNotFoundException(99L));

        mockMvc.perform(get("/api/v1/orders/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").contains("99"));
    }

    @Test
    void deleteOrder_success_returns204() throws Exception {
        doNothing().when(orderService).deleteOrder(1L);

        mockMvc.perform(delete("/api/v1/orders/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteOrder_notFound_returns404() throws Exception {
        doThrow(new OrderNotFoundException(99L)).when(orderService).deleteOrder(99L);

        mockMvc.perform(delete("/api/v1/orders/99"))
                .andExpect(status().isNotFound());
    }
}
```

---

## Task 20: Contract Testing — Spring Cloud Contract (1hr)

### What You Learn
- Consumer-Driven Contract Testing
- Provider defines contracts as Groovy DSL or YAML
- Consumer generates stubs from contracts
- Provider verifies its implementation against contracts
- WireMock stubs for consumer tests

```groovy
// contracts/order-created.groovy (provider side)
Contract.make {
    description "should return 201 when creating valid order"
    request {
        method POST()
        url "/api/v1/orders"
        headers { contentType(applicationJson()) }
        body([
            customerName: "John Doe",
            customerEmail: "john@example.com",
            items: [[productName: "LAPTOP", quantity: 1, unitPrice: 2999.00]]
        ])
    }
    response {
        status 201
        headers { contentType(applicationJson()) }
        body([
            id: anyPositiveInt(),
            orderNumber: matching("ORD-\\d+"),
            status: "PENDING"
        ])
    }
}
```

```java
// Provider verification test
@SpringBootTest
@AutoConfigureStubRunner(
    ids = "com.ecommerce:order-service:+:stubs:8090",
    stubsMode = StubRunnerProperties.StubsMode.LOCAL
)
class OrderContractVerificationTest extends ContractVerifierBase {
    // Spring Cloud Contract generates and runs verification
}

// Consumer using stubs
@SpringBootTest
@AutoConfigureStubRunner(
    ids = "com.ecommerce:order-service:+:stubs:8090",
    stubsMode = StubRunnerProperties.StubsMode.LOCAL
)
class InventoryServiceConsumerTest {
    @Test
    void placeOrder_callsOrderService() {
        // Order service stub running on port 8090
        // Test that Inventory service correctly calls Order service API
    }
}
```

---

## Task 21: Advanced Testing — Coverage + Test Slices (1hr)

### JaCoCo Coverage
```xml
<!-- pom.xml -->
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <configuration>
        <rules>
            <rule>
                <limits>
                    <limit>
                        <counter>LINE</counter>
                        <value>COVEREDRATIO</value>
                        <minimum>0.80</minimum>  <!-- 80% minimum -->
                    </limit>
                </limits>
            </rule>
        </rules>
    </configuration>
</plugin>
```

```bash
# Generate coverage report
./mvnw test jacoco:report

# View: target/site/jacoco/index.html
# Build fails if < 80% coverage
```

### Test Slices Summary
| Annotation | What's Loaded | Use For |
|------------|--------------|---------|
| @WebMvcTest | Web layer only | Controller tests (fast) |
| @DataJpaTest | JPA layer only | Repository tests (fast) |
| @DataMongoTest | MongoDB layer | Mongo repository tests |
| @JsonTest | JSON serialization | ObjectMapper tests |
| @SpringBootTest | Full context | Integration tests (slow) |
| @WebFluxTest | WebFlux layer | Reactive controller tests |
| @RestClientTest | REST client | RestTemplate/WebClient tests |

### Parameterized Tests
```java
@ParameterizedTest
@CsvSource({
    "PENDING,   CONFIRMED, true",
    "CONFIRMED, SHIPPED,   true",
    "DELIVERED, CANCELLED, false",
})
void canTransition(OrderStatus from, OrderStatus to, boolean expected) {
    assertThat(orderStateMachine.canTransition(from, to)).isEqualTo(expected);
}

@ParameterizedTest
@MethodSource("invalidOrderRequests")
void createOrder_invalidInput_returnsBadRequest(OrderRequest request) {
    assertThatThrownBy(() -> orderService.createOrder(request))
            .isInstanceOf(ConstraintViolationException.class);
}

static Stream<OrderRequest> invalidOrderRequests() {
    return Stream.of(
        OrderRequest.builder().customerName("").build(),
        OrderRequest.builder().customerEmail("invalid").build(),
        OrderRequest.builder().items(List.of()).build()
    );
}
```

---

## ✅ Phase 3 Completion Checklist
- [ ] @ExtendWith(MockitoExtension.class) unit tests for all services
- [ ] ArgumentCaptor verifying what's saved to repository
- [ ] Exception scenarios tested in unit tests
- [ ] @ParameterizedTest for validation edge cases
- [ ] @SpringBootTest integration test for full flow
- [ ] TestContainers for real PostgreSQL
- [ ] TestContainers for real Kafka
- [ ] @DataJpaTest for all custom query methods
- [ ] TestEntityManager for test data setup
- [ ] @WebMvcTest for all controller endpoints
- [ ] Validation error response tested (400 errors)
- [ ] 404 response tested
- [ ] Spring Cloud Contract: 1 contract defined and verified
- [ ] JaCoCo coverage report: 80%+
- [ ] @ParameterizedTest for state machine transitions

---

## 💬 Key Interview Q&A

**Q: What is the difference between @Mock and @MockBean?**
A: @Mock is pure Mockito — no Spring context, fast. @MockBean creates a Mockito mock AND registers it in the Spring ApplicationContext, replacing any real bean. Use @Mock in unit tests (@ExtendWith(MockitoExtension.class)), @MockBean in Spring slice tests (@WebMvcTest, @SpringBootTest).

**Q: What is a Test Slice?**
A: Spring Boot auto-configuration that loads only the components needed for a specific layer test. @WebMvcTest loads only controllers, filters, security. @DataJpaTest loads only entities, repositories, JPA config. Faster than @SpringBootTest (partial context), but can only test that slice.

**Q: What are TestContainers and why use them?**
A: Library that starts real Docker containers during tests. Run actual PostgreSQL, Kafka, Redis instead of H2/embedded. Tests catch DB-specific behavior (PostgreSQL vs H2 differences). @Container + @DynamicPropertySource wires the container URL into Spring config automatically.

**Q: What is Consumer-Driven Contract Testing?**
A: Consumer defines the API contract it expects. Provider verifies its implementation meets the contract. Ensures backward compatibility when provider changes API. Catches integration bugs before deployment. Spring Cloud Contract: consumer gets WireMock stubs, provider gets generated verification tests.

**Q: @WebMvcTest vs @SpringBootTest — when to use which?**
A: @WebMvcTest for fast controller tests — no DB, no service (use @MockBean). Tests HTTP layer, serialization, validation, security, error handling. @SpringBootTest for full integration — real DB (TestContainers), real beans. Slower but verifies full stack. Use both: WebMvcTest for each endpoint, SpringBootTest for critical end-to-end flows.

---

## 🔗 Next Phase
**Phase 4: Security** — Spring Security, JWT, OAuth2, RBAC.
