package com.ecommerce.orderservice.service;

import java.util.Map;

/**
 * Exposes Hibernate's runtime query/session statistics so N+1 regressions
 * can be caught (collectionFetchCount >> collectionLoadCount). Requires
 * spring.jpa.properties.hibernate.generate_statistics=true. See
 * docs/phase1_task6.md.
 */
public interface StatisticsService {

    Map<String, Object> getHibernateStatistics();

    boolean isPotentialN1Problem();
}
