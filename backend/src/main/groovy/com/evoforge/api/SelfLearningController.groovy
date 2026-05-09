package com.evoforge.api

import com.evoforge.learning.SelfLearningDashboardService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping('/api/self-learning')
class SelfLearningController {
    private final SelfLearningDashboardService dashboardService

    SelfLearningController(SelfLearningDashboardService dashboardService) {
        this.dashboardService = dashboardService
    }

    @GetMapping('dashboard')
    Map<String, Object> dashboard(@RequestParam(name = 'limit', defaultValue = '80') int limit) {
        return dashboardService.dashboard(limit)
    }

    @PostMapping('startup')
    Map<String, Object> start() {
        dashboardService.startAutonomousLearningSideQuest()
        return dashboardService.dashboard()
    }
}
