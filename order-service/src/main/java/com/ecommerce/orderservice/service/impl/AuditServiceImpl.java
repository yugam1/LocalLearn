package com.ecommerce.orderservice.service.impl;

import com.ecommerce.orderservice.model.OrderAuditLog;
import com.ecommerce.orderservice.repository.OrderAuditLogRepository;
import com.ecommerce.orderservice.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit log must commit even if the caller's order-creation transaction
 * later rolls back — hence Propagation.REQUIRES_NEW on every method here.
 * See docs/phase1_task5.md, "REQUIRES_NEW — Key Interview Scenario".
 */
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT_LOGGER");

    private final OrderAuditLogRepository auditLogRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logOrderCreated(Long orderId, String userId) {
        auditLogRepository.save(OrderAuditLog.builder()
                .orderId(orderId)
                .action("ORDER_CREATED")
                .performedBy(userId)
                .build());
        AUDIT.info("Order created: orderId={}, userId={}", orderId, userId);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logOrderDeleted(Long orderId, String userId, String reason) {
        auditLogRepository.save(OrderAuditLog.builder()
                .orderId(orderId)
                .action("ORDER_DELETED")
                .performedBy(userId)
                .details(reason)
                .build());
        AUDIT.warn("Order deleted: orderId={}, userId={}, reason={}", orderId, userId, reason);
    }
}
