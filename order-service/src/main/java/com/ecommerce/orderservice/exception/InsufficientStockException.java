package com.ecommerce.orderservice.exception;

public class InsufficientStockException extends BusinessException {
    public InsufficientStockException(String productName, int requested, int available) {
        super(String.format(
                "Insufficient stock for '%s'. Requested: %d, Available: %d",
                productName, requested, available
        ));
    }
}
