package com.evoforge.api

import com.evoforge.codex.CodexBridgeService
import com.evoforge.codex.CodexQuestionBridgeService
import com.evoforge.core.EvoForgeProperties
import com.evoforge.tester.EvoForgeTesterService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping('/api/codex/bridge')
class CodexBridgeController {
    private final CodexBridgeService bridgeService
    private final EvoForgeTesterService testerService
    private final CodexQuestionBridgeService questionBridgeService
    private final EvoForgeProperties properties

    CodexBridgeController(CodexBridgeService bridgeService,
                          EvoForgeTesterService testerService,
                          CodexQuestionBridgeService questionBridgeService,
                          EvoForgeProperties properties) {
        this.bridgeService = bridgeService
        this.testerService = testerService
        this.questionBridgeService = questionBridgeService
        this.properties = properties
    }

    @GetMapping('manifest')
    Map<String, Object> manifest(@RequestParam(value = 'projectKey', required = false) String projectKey) {
        return bridgeService.manifest(projectKey, properties.codexTask.bridgeBaseUrl)
    }

    @PostMapping('query')
    Map<String, Object> query(@RequestBody Map<String, Object> request) {
        return bridgeService.query(request ?: [:], properties.codexTask.bridgeBaseUrl)
    }

    @PostMapping('test-plan')
    Map<String, Object> testPlan(@RequestBody Map<String, Object> request) {
        return testerService.plan(request ?: [:], properties.codexTask.bridgeBaseUrl)
    }

    @PostMapping('test-run')
    Map<String, Object> testRun(@RequestBody Map<String, Object> request) {
        return testerService.run(request ?: [:]).toMap(true)
    }

    @PostMapping('questions/ask')
    Map<String, Object> askQuestion(@RequestBody Map<String, Object> request) {
        return questionBridgeService.ask(request ?: [:])
    }

    @GetMapping('skills')
    List<Map<String, Object>> skills(@RequestParam(value = 'query', required = false) String query,
                                     @RequestParam(value = 'limit', required = false, defaultValue = '8') int limit,
                                     @RequestParam(value = 'enabledOnly', required = false, defaultValue = 'true') boolean enabledOnly) {
        return bridgeService.searchSkills(query ?: '', limit, enabledOnly)
    }

    @GetMapping('skills/{id}')
    Map<String, Object> skill(@PathVariable('id') String id) {
        return bridgeService.skillDetail(id, properties.codexTask.bridgeBaseUrl)
    }
}
