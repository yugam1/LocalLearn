package com.ecommerce.orderservice.service;

/**
 * Order audit trail. Implementations persist to the order_audit_log table
 * AND write to the AUDIT_LOGGER named SLF4J logger (see
 * docs/phase2_task7.md). Every method runs in Propagation.REQUIRES_NEW so
 * the audit entry survives even if the calling transaction rolls back.
 */
public interface AuditService {

    void logOrderCreated(Long orderId, String userId);

    void logOrderDeleted(Long orderId, String userId, String reason);
}
