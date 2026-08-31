package com.ecommerce.orderservice.exception;

public class InvalidOrderException extends BusinessException {
    public InvalidOrderException(String message) {
        super(message);
    }
}
