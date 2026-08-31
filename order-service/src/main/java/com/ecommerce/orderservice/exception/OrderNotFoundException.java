package com.ecommerce.orderservice.exception;

public class OrderNotFoundException extends BusinessException {
    public OrderNotFoundException(Long id) {
        super("Order not found with ID: " + id);
    }

    public OrderNotFoundException(String orderNumber) {
        super("Order not found with order number: " + orderNumber);
    }
}
