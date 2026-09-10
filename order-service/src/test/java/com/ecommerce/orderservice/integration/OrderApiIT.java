package com.ecommerce.orderservice.integration;

import com.ecommerce.orderservice.dto.error.ErrorResponse;
import com.ecommerce.orderservice.dto.request.OrderRequest;
import com.ecommerce.orderservice.dto.response.OrderResponse;
import com.ecommerce.orderservice.model.Order;
import com.ecommerce.orderservice.model.OrderStatus;
import com.ecommerce.orderservice.model.Product;
import com.ecommerce.orderservice.repository.OrderRepository;
import com.ecommerce.orderservice.repository.ProductRepository;
import com.ecommerce.orderservice.support.AbstractPostgresIT;
import com.ecommerce.orderservice.support.TestData;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 17 — end-to-end integration test (docs/phase3_tasks13_to_18.md).
 *
 * <p>Full application context on a random port, talking to a real PostgreSQL
 * and a real Kafka broker. Unlike the slice tests, {@code @Transactional} is
 * genuinely active here — this is the only layer of the suite that can prove
 * propagation, rollback, and the savepoint behaviour of batch creation.
 *
 * <p>Note this class does <em>not</em> use {@code @Transactional} itself: the
 * HTTP call runs on a different thread than the test, so a test-managed
 * transaction would neither be visible to the server nor roll back its work.
 * Cleanup is therefore explicit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Order API (end-to-end, real PostgreSQL + Kafka)")
class OrderApiIT extends AbstractPostgresIT {

    private static final String BASE = "/api/v1/orders";

    /** KRaft mode — no ZooKeeper sidecar, so the broker is up in a few seconds. */
    private static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private ProductRepository productRepository;

    private static Consumer<String, String> orderCreatedConsumer;

    @BeforeEach
    void resetState() {
        orderRepository.deleteAll();
        productRepository.deleteAll();
    }

    @AfterAll
    static void closeConsumer() {
        if (orderCreatedConsumer != null) {
            orderCreatedConsumer.close();
        }
    }

    /** Raw string consumer — asserts what actually landed on the wire, payload shape included. */
    private static Consumer<String, String> orderCreatedConsumer() {
        if (orderCreatedConsumer == null) {
            Map<String, Object> props = new HashMap<>(KafkaTestUtils.consumerProps(
                    KAFKA.getBootstrapServers(), "order-api-it-" + System.currentTimeMillis(), "true"));
            props.put("key.deserializer", StringDeserializer.class);
            props.put("value.deserializer", StringDeserializer.class);
            props.put("auto.offset.reset", "earliest");
            orderCreatedConsumer = new org.apache.kafka.clients.consumer.KafkaConsumer<>(props);
            orderCreatedConsumer.subscribe(Collections.singletonList("order.created"));
            orderCreatedConsumer.poll(Duration.ofSeconds(2)); // force assignment
        }
        return orderCreatedConsumer;
    }

    @Test
    void createOrder_persistsOrderAndItemsAndReturns201() {
        ResponseEntity<OrderResponse> response =
                restTemplate.postForEntity(BASE, TestData.orderRequest(), OrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isNotNull();
        assertThat(response.getBody().getOrderNumber()).startsWith("ORD-");
        assertThat(response.getBody().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.getBody().getTotalAmount()).isEqualByComparingTo(TestData.EXPECTED_TOTAL);
        assertThat(response.getBody().getItems()).hasSize(2);

        assertThat(orderRepository.count()).isEqualTo(1);
        Order persisted = orderRepository.findByIdWithItems(response.getBody().getId()).orElseThrow();
        assertThat(persisted.getItems()).hasSize(2);
        assertThat(persisted.getVersion()).isZero();
    }

    @Test
    void createOrder_publishesOrderCreatedToKafka() {
        Consumer<String, String> consumer = orderCreatedConsumer();
        consumer.poll(Duration.ofMillis(200)); // drain anything from earlier tests

        ResponseEntity<OrderResponse> response =
                restTemplate.postForEntity(BASE, TestData.orderRequest(), OrderResponse.class);
        Long orderId = response.getBody().getId();

        ConsumerRecords<String, String> records =
                KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(15), 1);

        assertThat(records.count()).isPositive();
        ConsumerRecord<String, String> record = records.iterator().next();
        // Key is the order id (or a vip-/bulk- prefixed variant) so a given
        // order always lands on the same partition and stays ordered.
        assertThat(record.key()).endsWith(orderId.toString());
        assertThat(record.value())
                .contains("\"eventType\":\"ORDER_CREATED\"")
                .contains("\"orderId\":" + orderId);
        assertThat(record.headers().lastHeader("correlationId")).isNotNull();
    }

    @Test
    void createOrder_invalidPayload_returns400WithFieldErrorsAndPersistsNothing() {
        OrderRequest invalid = TestData.orderRequest("", "not-an-email");

        ResponseEntity<ErrorResponse> response =
                restTemplate.postForEntity(BASE, invalid, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Validation failed for request");
        assertThat(response.getBody().getErrors())
                .extracting(error -> error.getField())
                .contains("customerName", "customerEmail");
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void getOrder_notFound_returns404() {
        ResponseEntity<ErrorResponse> response =
                restTemplate.getForEntity(BASE + "/999999", ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getMessage()).contains("999999");
        assertThat(response.getBody().getPath()).isEqualTo(BASE + "/999999");
    }

    @Test
    void createThenGet_roundTripsTheOrder() {
        Long id = restTemplate.postForEntity(BASE, TestData.orderRequest(), OrderResponse.class)
                .getBody().getId();

        ResponseEntity<OrderResponse> response =
                restTemplate.getForEntity(BASE + "/" + id, OrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getId()).isEqualTo(id);
        assertThat(response.getBody().getItems())
                .extracting(item -> item.getProductName())
                .containsExactlyInAnyOrder("Laptop", "Mouse");
    }

    @Test
    void updateOrder_replacesItemsAndBumpsTheVersion() {
        Long id = restTemplate.postForEntity(BASE, TestData.orderRequest(), OrderResponse.class)
                .getBody().getId();

        OrderRequest update = OrderRequest.builder()
                .customerName("Jane Roe")
                .customerEmail("jane@example.com")
                .items(Collections.singletonList(TestData.itemRequest("Keyboard", 3, "50.00")))
                .build();

        ResponseEntity<OrderResponse> response = restTemplate.exchange(
                BASE + "/" + id, HttpMethod.PUT, new HttpEntity<>(update), OrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getCustomerName()).isEqualTo("Jane Roe");
        assertThat(response.getBody().getTotalAmount()).isEqualByComparingTo("150.00");

        Order persisted = orderRepository.findByIdWithItems(id).orElseThrow();
        assertThat(persisted.getItems())
                .extracting(item -> item.getProductName())
                .containsExactly("Keyboard");
        assertThat(persisted.getVersion()).isEqualTo(1L);
    }

    @Test
    void deleteOrder_returns204AndRemovesTheRow() {
        Long id = restTemplate.postForEntity(BASE, TestData.orderRequest(), OrderResponse.class)
                .getBody().getId();

        ResponseEntity<Void> response = restTemplate.exchange(
                BASE + "/" + id, HttpMethod.DELETE, HttpEntity.EMPTY, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(orderRepository.findById(id)).isEmpty();
    }

    @Test
    void searchOrders_filtersAndPaginates() {
        restTemplate.postForEntity(BASE, TestData.orderRequest(), OrderResponse.class);
        restTemplate.postForEntity(BASE,
                TestData.orderRequest("Other Buyer", "other@example.com"), OrderResponse.class);

        ResponseEntity<String> response = restTemplate.getForEntity(
                BASE + "?customerEmail=" + TestData.CUSTOMER_EMAIL + "&page=0&size=10", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"totalElements\":1")
                .contains(TestData.CUSTOMER_EMAIL);
    }

    /**
     * Inventory reservation runs with Propagation.MANDATORY inside the create
     * transaction. When stock is short it throws, so the whole transaction —
     * order, items, and the stock decrement — must roll back together.
     */
    @Test
    void createOrder_insufficientStock_returns422AndRollsBackEverything() {
        productRepository.save(Product.builder().name("Laptop").stock(0).build());

        ResponseEntity<ErrorResponse> response =
                restTemplate.postForEntity(BASE, TestData.orderRequest(), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().getMessage()).contains("Insufficient stock for 'Laptop'");
        assertThat(orderRepository.count()).isZero();
        assertThat(productRepository.findByName("Laptop").orElseThrow().getStock()).isZero();
    }

    @Test
    void createOrder_sufficientStock_decrementsInventoryInTheSameTransaction() {
        productRepository.save(Product.builder().name("Laptop").stock(5).build());
        productRepository.save(Product.builder().name("Mouse").stock(5).build());

        ResponseEntity<OrderResponse> response =
                restTemplate.postForEntity(BASE, TestData.orderRequest(), OrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(productRepository.findByName("Laptop").orElseThrow().getStock()).isEqualTo(4);
        assertThat(productRepository.findByName("Mouse").orElseThrow().getStock()).isEqualTo(3);
    }

    /**
     * The batch endpoint runs each element in its own {@code Propagation.NESTED}
     * savepoint. Only a real transaction manager over a savepoint-capable
     * driver can demonstrate this, which is why it lives here and not in the
     * unit tests: element 1 fails on stock, and element 0 must survive.
     */
    @Test
    void createOrdersBatch_oneElementFails_othersAreStillCommitted() {
        productRepository.save(Product.builder().name("Laptop").stock(1).build());
        productRepository.save(Product.builder().name("Mouse").stock(10).build());

        ResponseEntity<String> response = restTemplate.postForEntity(
                BASE + "/batch",
                java.util.Arrays.asList(TestData.orderRequest(), TestData.orderRequest()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"requestIndex\":0", "\"success\":true")
                .contains("\"requestIndex\":1", "\"success\":false")
                .contains("Insufficient stock for 'Laptop'");

        // The successful element committed; the failed one rolled back to its savepoint.
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(productRepository.findByName("Laptop").orElseThrow().getStock()).isZero();
        assertThat(productRepository.findByName("Mouse").orElseThrow().getStock()).isEqualTo(8);
    }

    @Test
    void revenueEndpoint_sumsTodaysOrders() {
        restTemplate.postForEntity(BASE, TestData.orderRequest(), OrderResponse.class);
        restTemplate.postForEntity(BASE, TestData.orderRequest(), OrderResponse.class);

        String today = java.time.LocalDate.now().toString();
        ResponseEntity<BigDecimal> response =
                restTemplate.getForEntity(BASE + "/revenue?date=" + today, BigDecimal.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .isEqualByComparingTo(TestData.EXPECTED_TOTAL.multiply(BigDecimal.valueOf(2)));
    }

    @Test
    void correlationIdHeader_isEchoedBackOnEveryResponse() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-ID", "REQ-it-42");

        ResponseEntity<String> response = restTemplate.exchange(
                BASE + "/999999", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getHeaders().getFirst("X-Correlation-ID")).isEqualTo("REQ-it-42");
    }
}
