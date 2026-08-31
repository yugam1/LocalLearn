package com.ecommerce.orderservice.exception;

/**
 * Base class for all business-rule exceptions. Extends RuntimeException so
 * call sites stay clean (no `throws` boilerplate) and exceptions bubble up
 * naturally to {@link GlobalExceptionHandler}.
 */
public abstract class BusinessException extends RuntimeException {
    protected BusinessException(String message) {
        super(message);
    }

    protected BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}
