package com.ecommerce.orderservice.kafka.partitioner;

import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.common.Cluster;

import java.util.Map;

/**
 * Business-rule-based partition routing (docs/phase2_task11.md, "Custom
 * Partitioner"). {@link com.ecommerce.orderservice.service.impl.KafkaProducerServiceImpl}
 * builds keys of the shape {@code "vip-<orderId>"} / {@code "bulk-<orderId>"}
 * / {@code "<orderId>"} — high-value orders (VIP) and high-quantity orders
 * (bulk) get dedicated partitions so they can be scaled/monitored
 * independently, everything else falls back to hash-based routing (still
 * ordered per order, since the order id is always part of the key).
 */
public class OrderPartitioner implements Partitioner {

    private static final int VIP_PARTITION = 0;
    private static final int BULK_PARTITION = 1;

    @Override
    public int partition(String topic, Object key, byte[] keyBytes,
                          Object value, byte[] valueBytes, Cluster cluster) {
        int partitions = cluster.partitionCountForTopic(topic);
        if (partitions <= 0) {
            return 0;
        }
        if (key == null) {
            // No key → spread round-robin-ish across partitions by time.
            return (int) (System.currentTimeMillis() % partitions);
        }
        String k = key.toString();
        if (k.startsWith("vip-")) {
            return VIP_PARTITION % partitions;
        }
        if (k.startsWith("bulk-")) {
            return BULK_PARTITION % partitions;
        }
        return Math.abs(k.hashCode()) % partitions;
    }

    @Override
    public void close() {
        // No resources to release.
    }

    @Override
    public void configure(Map<String, ?> configs) {
        // No configuration required.
    }
}
