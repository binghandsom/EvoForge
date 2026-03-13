package com.evoforge.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Service

import java.util.concurrent.TimeUnit

@Service
class SkillMetricsService {
    private final MeterRegistry registry

    SkillMetricsService(MeterRegistry registry) {
        this.registry = registry
    }

    void recordExecution(String skillId, boolean success, long durationMs) {
        Timer timer = Timer.builder('evoforge.skill.execution')
            .tag('skillId', skillId ?: 'unknown')
            .tag('success', success.toString())
            .register(registry)
        timer.record(durationMs, TimeUnit.MILLISECONDS)

        Counter.builder('evoforge.skill.execution.count')
            .tag('skillId', skillId ?: 'unknown')
            .tag('success', success.toString())
            .register(registry)
            .increment()
    }
}
