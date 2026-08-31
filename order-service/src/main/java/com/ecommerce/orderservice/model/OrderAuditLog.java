package com.ecommerce.orderservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Audit trail row for order lifecycle events. Written from
 * {@code AuditServiceImpl} inside a {@code REQUIRES_NEW} transaction so the
 * audit entry survives even if the caller's transaction later rolls back —
 * see docs/phase1_task5.md "REQUIRES_NEW — Key Interview Scenario".
 */
@Entity
@Table(name = "order_audit_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(name = "performed_by", length = 150)
    private String performedBy;

    @Column(length = 500)
    private String details;

    @CreationTimestamp
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;
}
