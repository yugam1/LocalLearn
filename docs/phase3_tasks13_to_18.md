# Phase 3 — Testing (Tasks 13–18)
**Estimated Time:** 6 hours | **Status:** ⬜ Not Started

---

## Task 13 (Unit Testing — Service Layer with Mockito)

### Test Annotations
```java
@ExtendWith(MockitoExtension.class)  // JUnit 5 + Mockito
class OrderServiceTest {
    @Mock OrderRepository orderRepository;
    @Mock KafkaProducerService kafkaProducerService;
    @Mock AuditService auditService;
    @Captor ArgumentCaptor<Order> orderCaptor;
    @InjectMocks OrderServiceImpl orderService;  // Creates instance, injects mocks
}
```

### Mockito Patterns
```java
// Stubbing
when(repo.findByIdWithItems(1L)).thenReturn(Optional.of(order));
when(repo.save(any(Order.class))).thenReturn(savedOrder);
when(repo.findById(99L)).thenReturn(Optional.empty());
doNothing().when(auditService).logOrderCreated(any(), any());
doThrow(new RuntimeException("DB error")).when(repo).save(any());

// Verification
verify(kafkaProducerService).publishOrderCreated(any(OrderCreatedEvent.class));
verify(repo, times(1)).save(any(Order.class));
verify(repo, never()).delete(any(Order.class));
verifyNoMoreInteractions(kafkaProducerService);

// Argument capture
verify(repo).save(orderCaptor.capture());
Order saved = orderCaptor.getValue();
assertThat(saved.getCustomerEmail()).isEqualTo("john@example.com");
```

### Test Template
```java
@Test
void createOrder_validRequest_createsOrderAndPublishesEvent() {
    // Arrange
    OrderRequest req = buildRequest("John", "john@example.com");
    Order savedOrder = buildOrder(1L, "ORD-123");
    when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);

    // Act
    OrderResponse result = orderService.createOrder(req);

    // Assert
    assertThat(result.getId()).isEqualTo(1L);
    assertThat(result.getOrderNumber()).isEqualTo("ORD-123");
    verify(kafkaProducerService).publishOrderCreated(any(OrderCreatedEvent.class));
    verify(auditService).logOrderCreated(eq(1L), eq("john@example.com"));
}

@Test
void getOrderById_notFound_throwsOrderNotFoundException() {
    when(orderRepository.findByIdWithItems(999L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> orderService.getOrderById(999L))
        .isInstanceOf(OrderNotFoundException.class)
        .hasMessageContaining("999");
    verify(kafkaProducerService, never()).publishOrderCreated(any());
}
```

### Mockito Spy
```java
@Spy
List<String> realList = new ArrayList<>();

doReturn(5).when(realList).size(); // Stub one method
realList.add("item");               // Real method still called
```

---

## Task 14 (Integration Testing — @SpringBootTest)

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY) // Use H2
class OrderControllerIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired OrderRepository orderRepository;

    @BeforeEach
    void setup() { orderRepository.deleteAll(); }

    @Test
    void createOrder_validRequest_returns201WithOrder() {
        OrderRequest req = buildRequest();
        ResponseEntity<OrderResponse> res = restTemplate.postForEntity(
            "/api/v1/orders", req, OrderResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().getOrderNumber()).startsWith("ORD-");
        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @Test
    void getOrder_notFound_returns404() {
        ResponseEntity<ErrorResponse> res = restTemplate.getForEntity(
            "/api/v1/orders/999", ErrorResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().getMessage()).contains("999");
    }
}
```

### With TestContainers (Real PostgreSQL + Kafka in Tests)
```java
@SpringBootTest
@Testcontainers
class OrderServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb").withUsername("test").withPassword("test");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }
}
```

---

## Task 15 (Repository Testing — @DataJpaTest)

```java
@DataJpaTest                          // Only JPA layer — no web, no services
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class OrderRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired OrderRepository orderRepository;
    @Autowired TestEntityManager entityManager;

    @Test
    void findByIdWithItems_returnsOrderWithItemsInSingleQuery() {
        Order order = buildOrder();
        order.addItem(buildItem("Laptop", 1, new BigDecimal("2999.00")));
        order.addItem(buildItem("Mouse", 2, new BigDecimal("79.99")));
        entityManager.persistAndFlush(order);
        entityManager.clear(); // Detach — force fresh DB load

        Optional<Order> found = orderRepository.findByIdWithItems(order.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getItems()).hasSize(2);
        // Verify items were loaded (not lazy-loaded separately)
    }

    @Test
    void findByStatus_returnsPendingOrders() {
        entityManager.persistAndFlush(buildOrder(OrderStatus.PENDING));
        entityManager.persistAndFlush(buildOrder(OrderStatus.CONFIRMED));
        entityManager.persistAndFlush(buildOrder(OrderStatus.PENDING));

        List<Order> pending = orderRepository.findByStatus(OrderStatus.PENDING);

        assertThat(pending).hasSize(2);
    }
}
```

---

## Task 16 (Controller Testing — @WebMvcTest)

```java
@WebMvcTest(OrderController.class)    // Only web layer — no DB, no Kafka
class OrderControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean OrderService orderService; // @MockBean = mock in Spring context

    @Test
    void createOrder_validRequest_returns201() throws Exception {
        OrderResponse mockResponse = buildResponse(1L, "ORD-123");
        when(orderService.createOrder(any(OrderRequest.class))).thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(buildRequest())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(1L))
            .andExpect(jsonPath("$.orderNumber").value("ORD-123"))
            .andExpect(header().exists("Content-Type"));
    }

    @Test
    void createOrder_blankCustomerName_returns400WithValidationError() throws Exception {
        OrderRequest invalidReq = OrderRequest.builder()
                .customerName("").customerEmail("john@example.com")
                .items(List.of(buildItemRequest()))
                .build();

        mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidReq)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0].field").value("customerName"))
            .andExpect(jsonPath("$.errors[0].message").value("Customer name is required"));
    }

    @Test
    void getOrder_notFound_returns404() throws Exception {
        when(orderService.getOrderById(99L)).thenThrow(new OrderNotFoundException(99L));

        mockMvc.perform(get("/api/v1/orders/99"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("Order not found with ID: 99"));
    }
}
```

---

## Test Slice Comparison

| Annotation | Context | DB | Kafka | Web | Use For |
|---|---|---|---|---|---|
| `@SpringBootTest` | Full | ✅ | ✅ | ✅ | End-to-end tests |
| `@WebMvcTest` | Web only | ❌ | ❌ | ✅ | Controller unit tests |
| `@DataJpaTest` | JPA only | ✅ | ❌ | ❌ | Repository unit tests |
| `@DataRedisTest` | Redis only | ❌ | ❌ | ❌ | Cache tests |

---

## JaCoCo Coverage

```xml
<!-- pom.xml -->
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <configuration>
        <excludes>
            <exclude>**/model/**</exclude>       <!-- JPA entities -->
            <exclude>**/dto/**</exclude>          <!-- DTOs -->
            <exclude>**/config/**</exclude>       <!-- Config classes -->
            <exclude>**/*Application.class</exclude>
        </excludes>
    </configuration>
    <executions>
        <execution>
            <goals><goal>prepare-agent</goal></goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>test</phase>
            <goals><goal>report</goal></goals>
        </execution>
        <execution>
            <id>check</id>
            <goals><goal>check</goal></goals>
            <configuration>
                <rules>
                    <rule>
                        <limits>
                            <limit>
                                <counter>LINE</counter>
                                <value>COVEREDRATIO</value>
                                <minimum>0.80</minimum>  <!-- 80% line coverage -->
                            </limit>
                        </limits>
                    </rule>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

---

## Interview Q&A

**Q: Mock vs Spy?**
Mock: completely fake object — all methods return null/0/false by default. Spy: wraps real object — calls real methods unless stubbed. Use Mock for external dependencies. Use Spy for partial mocking of real objects.

**Q: @WebMvcTest vs @SpringBootTest?**
@WebMvcTest: only web layer, uses MockMvc, other layers mocked with @MockBean — fast, focused. @SpringBootTest: full context, slower, true integration. Use @WebMvcTest for controller tests, @SpringBootTest for end-to-end.

**Q: What is TestContainers?**
Docker-based test infrastructure: spins up real PostgreSQL/Kafka/Redis containers during tests. Tests run against real databases (not in-memory H2). @Container + @DynamicPropertySource to wire container URLs into Spring properties.

**Q: @InjectMocks vs @Autowired?**
@InjectMocks: Mockito creates instance and injects @Mock fields via constructor/setter — no Spring context needed. @Autowired: Spring context manages bean creation and injection — needs @SpringBootTest or equivalent.

**Q: How do you test exception handling?**
assertThatThrownBy: `assertThatThrownBy(() -> service.getOrderById(99L)).isInstanceOf(OrderNotFoundException.class).hasMessageContaining("99")`. Or: `assertThrows(OrderNotFoundException.class, () -> service.getOrderById(99L))`.

**Q: What does @DataJpaTest load?**
Only JPA components: entities, repositories, Hibernate, in-memory H2 by default. Does NOT load: controllers, services, Kafka, Redis. Ideal for testing repository queries in isolation.
