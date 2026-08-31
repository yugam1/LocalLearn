package com.ecommerce.orderservice.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = OrderQuantityValidator.class)
@Documented
public @interface ValidOrderQuantity {
    String message() default "Invalid order quantity";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
