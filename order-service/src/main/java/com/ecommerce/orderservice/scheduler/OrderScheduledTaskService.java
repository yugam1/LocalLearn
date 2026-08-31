package com.ecommerce.orderservice.scheduler;

import com.ecommerce.orderservice.model.Order;
import com.ecommerce.orderservice.model.OrderStatus;
import com.ecommerce.orderservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Business-facing scheduled jobs, built entirely on {@link OrderRepository}'s
 * existing read methods (no repository changes needed). See
 * docs/phase2_task9.md, "Business Tasks".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderScheduledTaskService {

    private static final long PENDING_ORDER_ALERT_THRESHOLD = 100;

    private final OrderRepository orderRepository;

    /** Daily 2 AM: surfaces pending orders that have been sitting for over a week. */
    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional(readOnly = true)
    public void cleanupOldPendingOrders() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
            long staleCount = orderRepository.findByStatus(OrderStatus.PENDING).stream()
                    .map(Order::getOrderDate)
                    .filter(orderDate -> orderDate != null && orderDate.isBefore(cutoff))
                    .count();
            log.info("[CLEANUP] {} pending orders older than 7 days found", staleCount);
        } catch (Exception e) {
            log.error("[CLEANUP] pending-order sweep failed", e);
        }
    }

    /** Daily 11 PM: today's revenue + outstanding pending count, for an ops summary. */
    @Scheduled(cron = "0 0 23 * * ?")
    @Transactional(readOnly = true)
    public void generateDailySummary() {
        try {
            LocalDateTime start = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
            BigDecimal todayRevenue = orderRepository.sumTotalAmountBetween(start, LocalDateTime.now());
            long pendingCount = orderRepository.countByStatus(OrderStatus.PENDING);
            log.info("[DAILY-SUMMARY] revenueToday={}, pendingOrders={}", todayRevenue, pendingCount);
        } catch (Exception e) {
            log.error("[DAILY-SUMMARY] generation failed", e);
        }
    }

    /** Every 5 minutes: alert if the pending queue is backing up. */
    @Scheduled(cron = "0 0/5 * * * ?")
    @Transactional(readOnly = true)
    public void monitorPendingOrders() {
        try {
            long pendingCount = orderRepository.countByStatus(OrderStatus.PENDING);
            if (pendingCount > PENDING_ORDER_ALERT_THRESHOLD) {
                log.warn("[MONITOR] HIGH PENDING ORDER COUNT: {}", pendingCount);
            } else {
                log.info("[MONITOR] pendingOrders={}", pendingCount);
            }
        } catch (Exception e) {
            log.error("[MONITOR] pending-order check failed", e);
        }
    }
}
