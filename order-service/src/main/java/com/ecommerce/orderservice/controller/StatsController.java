package com.ecommerce.orderservice.controller;

import com.ecommerce.orderservice.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Exposes Hibernate session statistics for N+1 detection during
 * development. See docs/phase1_task6.md.
 */
@RestController
@RequestMapping("/api/v1/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatisticsService statisticsService;

    @GetMapping("/hibernate")
    public ResponseEntity<Map<String, Object>> hibernateStatistics() {
        return ResponseEntity.ok(statisticsService.getHibernateStatistics());
    }
}
