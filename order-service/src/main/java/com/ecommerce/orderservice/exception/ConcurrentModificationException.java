package com.ecommerce.orderservice.exception;

/**
 * Thrown when an optimistic-lock (@Version) conflict is detected while
 * saving an entity. Deliberately named to shadow java.util's exception of
 * the same name within this package — never import java.util's version
 * alongside this one.
 */
public class ConcurrentModificationException extends BusinessException {
    public ConcurrentModificationException(String entityType, Long id) {
        super(String.format(
                "Concurrent modification detected for %s with ID: %d. Please refresh and retry.",
                entityType, id
        ));
    }
}
