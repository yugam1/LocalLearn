package com.ecommerce.orderservice.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Custom business-rule validator demonstrating the ConstraintValidator SPI.
 * The @Min/@Max annotations on OrderItemRequest.quantity already enforce
 * this range declaratively; this class exists to show how a validator with
 * custom messaging/logic is wired (see docs/phase1_task2.md, Step 8).
 */
public class OrderQuantityValidator implements ConstraintValidator<ValidOrderQuantity, Integer> {

    @Override
    public boolean isValid(Integer quantity, ConstraintValidatorContext context) {
        if (quantity == null) {
            return true; // @NotNull handles null separately
        }
        if (quantity < 1 || quantity > 1000) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                    "Quantity must be between 1 and 1000"
            ).addConstraintViolation();
            return false;
        }
        return true;
    }
}
