package com.evoforge.learning

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class SelfLearningStartup {
    private static final Logger log = LoggerFactory.getLogger(SelfLearningStartup)

    private final SelfLearningDashboardService dashboardService

    SelfLearningStartup(SelfLearningDashboardService dashboardService) {
        this.dashboardService = dashboardService
    }

    @EventListener(ApplicationReadyEvent)
    void onApplicationReady() {
        try {
            dashboardService.startAutonomousLearningSideQuest()
        } catch (Exception ex) {
            log.warn('Self-learning startup side quest failed: {}', ex.message, ex)
        }
    }
}
