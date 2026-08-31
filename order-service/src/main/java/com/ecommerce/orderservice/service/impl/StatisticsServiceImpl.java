package com.ecommerce.orderservice.service.impl;

import com.ecommerce.orderservice.service.StatisticsService;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {

    private final EntityManagerFactory entityManagerFactory;

    @Override
    public Map<String, Object> getHibernateStatistics() {
        Statistics stats = statistics();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("queryExecutionCount", stats.getQueryExecutionCount());
        summary.put("entityLoadCount", stats.getEntityLoadCount());
        summary.put("collectionLoadCount", stats.getCollectionLoadCount());
        summary.put("collectionFetchCount", stats.getCollectionFetchCount());
        summary.put("prepareStatementCount", stats.getPrepareStatementCount());
        summary.put("connectCount", stats.getConnectCount());
        summary.put("potentialN1Problem", isPotentialN1Problem());
        return summary;
    }

    @Override
    public boolean isPotentialN1Problem() {
        Statistics stats = statistics();
        return stats.getCollectionFetchCount() > stats.getCollectionLoadCount() * 2L;
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }
}
