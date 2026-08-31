package com.ecommerce.orderservice.specification;

import com.ecommerce.orderservice.model.Order;
import com.ecommerce.orderservice.model.OrderStatus;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;

/**
 * Composable, null-safe JPA Criteria specifications for Order. Combine via
 * Specification.where(...).and(...) — see docs/phase1_task4.md.
 */
public final class OrderSpecification {

    private OrderSpecification() {
    }

    public static Specification<Order> hasCustomerEmail(String email) {
        return (root, query, cb) -> email == null
                ? cb.conjunction()
                : cb.equal(root.get("customerEmail"), email);
    }

    public static Specification<Order> hasStatus(OrderStatus status) {
        return (root, query, cb) -> status == null
                ? cb.conjunction()
                : cb.equal(root.get("status"), status);
    }

    public static Specification<Order> hasTotalAmountBetween(BigDecimal min, BigDecimal max) {
        return (root, query, cb) -> {
            if (min == null && max == null) {
                return cb.conjunction();
            }
            if (min != null && max != null) {
                return cb.between(root.get("totalAmount"), min, max);
            }
            if (min != null) {
                return cb.greaterThanOrEqualTo(root.get("totalAmount"), min);
            }
            return cb.lessThanOrEqualTo(root.get("totalAmount"), max);
        };
    }
}
