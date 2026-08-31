package com.ecommerce.orderservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pause/resume/status for every registered {@code @KafkaListener} container.
 * See docs/phase2_task11.md, "Offset Management" — pausing stops polling
 * (messages accumulate in Kafka within retention); Kafka tracks the offset,
 * so resuming continues exactly where it left off.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaOffsetService {

    private final KafkaListenerEndpointRegistry registry;

    public void pauseAll() {
        registry.getAllListenerContainers().forEach(container -> {
            if (container.isRunning()) {
                container.pause();
            }
        });
        log.info("Paused all Kafka listener containers");
    }

    public void resumeAll() {
        registry.getAllListenerContainers().forEach(container -> {
            if (container.isPauseRequested()) {
                container.resume();
            }
        });
        log.info("Resumed all Kafka listener containers");
    }

    public Map<String, Object> getStatus() {
        return registry.getAllListenerContainers().stream()
                .collect(Collectors.toMap(
                        this::containerKey,
                        container -> Map.of(
                                "running", container.isRunning(),
                                "paused", container.isPauseRequested())));
    }

    private String containerKey(MessageListenerContainer container) {
        return container.getListenerId() != null ? container.getListenerId() : container.toString();
    }
}
