package com.ecommerce.orderservice.validation;

import com.ecommerce.orderservice.dto.request.OrderItemRequest;
import com.ecommerce.orderservice.dto.request.OrderRequest;
import com.ecommerce.orderservice.support.TestData;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 21 — parameterized tests against the Bean Validation constraints
 * (docs/phase3_tasks13_to_18.md).
 *
 * <p>Driving the {@link Validator} directly, with no Spring context and no
 * MockMvc, is the fastest way to pin down every boundary of every constraint;
 * {@code OrderControllerTest} then only has to prove that a violation becomes
 * a 400 with the right JSON shape.
 */
@DisplayName("OrderRequest / OrderItemRequest constraints")
class OrderRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void openValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    private static Set<ConstraintViolation<OrderRequest>> validate(OrderRequest request) {
        return validator.validate(request);
    }

    private static Set<ConstraintViolation<OrderItemRequest>> validate(OrderItemRequest item) {
        return validator.validate(item);
    }

    @Test
    void fullyPopulatedRequest_hasNoViolations() {
        assertThat(validate(TestData.orderRequest())).isEmpty();
    }

    @ParameterizedTest(name = "customerName=\"{0}\" is rejected")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "A"})
    void invalidCustomerName_isRejected(String customerName) {
        OrderRequest request = TestData.orderRequest(customerName, TestData.CUSTOMER_EMAIL);

        assertThat(validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("customerName");
    }

    @ParameterizedTest(name = "customerName of length {0} is accepted")
    @ValueSource(ints = {2, 50, 100})
    void customerNameWithinBounds_isAccepted(int length) {
        OrderRequest request = TestData.orderRequest("x".repeat(length), TestData.CUSTOMER_EMAIL);

        assertThat(validate(request)).isEmpty();
    }

    @Test
    void customerNameOver100Chars_isRejected() {
        OrderRequest request = TestData.orderRequest("x".repeat(101), TestData.CUSTOMER_EMAIL);

        assertThat(validate(request))
                .singleElement()
                .satisfies(violation -> assertThat(violation.getMessage())
                        .isEqualTo("Customer name must be between 2 and 100 characters"));
    }

    @ParameterizedTest(name = "email \"{0}\" is rejected")
    @ValueSource(strings = {"not-an-email", "@example.com", "spaces in@example.com"})
    void invalidEmail_isRejected(String email) {
        OrderRequest request = TestData.orderRequest(TestData.CUSTOMER_NAME, email);

        assertThat(validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("customerEmail");
    }

    /**
     * {@code @Email} only enforces a loose {@code something@something} shape —
     * it does not require a TLD and does not do a DNS lookup. Anything
     * stricter has to be a custom constraint or a verification email.
     */
    @ParameterizedTest(name = "email \"{0}\" is accepted")
    @ValueSource(strings = {"john@example.com", "j.doe+tag@sub.example.co.uk", "missing@domain"})
    void validEmail_isAccepted(String email) {
        assertThat(validate(TestData.orderRequest(TestData.CUSTOMER_NAME, email))).isEmpty();
    }

    @Test
    void emptyItemList_isRejected() {
        OrderRequest request = TestData.orderRequest();
        request.setItems(Collections.emptyList());

        assertThat(validate(request))
                .singleElement()
                .satisfies(violation -> assertThat(violation.getMessage())
                        .isEqualTo("Order must contain at least one item"));
    }

    /** @Valid on the list makes nested violations report an indexed path. */
    @Test
    void invalidNestedItem_reportsIndexedPropertyPath() {
        OrderRequest request = TestData.orderRequest();
        request.setItems(Collections.singletonList(TestData.itemRequest("Laptop", 0, "2999.00")));

        assertThat(validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("items[0].quantity");
    }

    @ParameterizedTest(name = "quantity={0} -> valid={1}")
    @CsvSource({
            "1,    true",
            "500,  true",
            "1000, true",
            "0,    false",
            "-1,   false",
            "1001, false"
    })
    void quantityBoundaries(int quantity, boolean expectedValid) {
        OrderItemRequest item = TestData.itemRequest("Laptop", quantity, "2999.00");

        assertThat(validate(item).isEmpty()).isEqualTo(expectedValid);
    }

    @ParameterizedTest(name = "unitPrice={0} -> valid={1}")
    @CsvSource({
            "0.01,       true",
            "2999.00,    true",
            "999999.99,  true",
            "0.00,       false",
            "-1.00,      false",
            "1000000.00, false",
            "10.123,     false"
    })
    void unitPriceBoundaries(String unitPrice, boolean expectedValid) {
        OrderItemRequest item = OrderItemRequest.builder()
                .productName("Laptop")
                .quantity(1)
                .unitPrice(new BigDecimal(unitPrice))
                .build();

        assertThat(validate(item).isEmpty()).isEqualTo(expectedValid);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("itemsWithMissingRequiredFields")
    void requiredItemFields_areRejectedWhenNull(String description, OrderItemRequest item,
                                                 String expectedPath) {
        assertThat(validate(item))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains(expectedPath);
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> itemsWithMissingRequiredFields() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        "null productName",
                        OrderItemRequest.builder().quantity(1).unitPrice(BigDecimal.ONE).build(),
                        "productName"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "null quantity",
                        OrderItemRequest.builder().productName("Laptop").unitPrice(BigDecimal.ONE).build(),
                        "quantity"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "null unitPrice",
                        OrderItemRequest.builder().productName("Laptop").quantity(1).build(),
                        "unitPrice"));
    }

    /**
     * All violations are collected in one pass, not short-circuited at the
     * first. Note {@code customerName=""} trips both {@code @NotBlank} and
     * {@code @Size}, so a single field can appear more than once — the API
     * returns one entry per violation, not per field.
     */
    @Test
    void multipleInvalidFields_reportEveryViolation() {
        OrderRequest request = OrderRequest.builder()
                .customerName("")
                .customerEmail("nope")
                .items(Collections.emptyList())
                .build();

        Set<ConstraintViolation<OrderRequest>> violations = validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsOnly("customerName", "customerEmail", "items");
        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("customerName", "customerEmail", "items");
        assertThat(violations).hasSize(4);
    }
}
