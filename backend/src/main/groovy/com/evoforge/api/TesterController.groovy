package com.evoforge.api

import com.evoforge.tester.TesterCapabilityService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping('/api/tester')
class TesterController {
    private final TesterCapabilityService capabilityService

    TesterController(TesterCapabilityService capabilityService) {
        this.capabilityService = capabilityService
    }

    @GetMapping('capabilities')
    List<Map<String, Object>> capabilities(@RequestParam(value = 'projectKey', required = false) String projectKey) {
        return capabilityService.list(projectKey ?: '').collect { it.toView() }
    }

    @PostMapping('capabilities/discover')
    Map<String, Object> discover(@RequestBody Map<String, Object> request) {
        return capabilityService.discover(request ?: [:])
    }

    @PostMapping('capabilities')
    Map<String, Object> create(@RequestBody Map<String, Object> request) {
        return capabilityService.save(request ?: [:]).toView()
    }

    @PutMapping('capabilities/{projectKey}/{id}')
    Map<String, Object> update(@PathVariable('projectKey') String projectKey,
                               @PathVariable('id') String id,
                               @RequestBody Map<String, Object> request) {
        Map<String, Object> payload = new LinkedHashMap<>(request ?: [:])
        payload.projectKey = projectKey
        payload.id = id
        return capabilityService.save(payload).toView()
    }

    @DeleteMapping('capabilities/{projectKey}/{id}')
    Map<String, Object> delete(@PathVariable('projectKey') String projectKey,
                               @PathVariable('id') String id) {
        capabilityService.delete(projectKey, id)
        return [deleted: true]
    }

    @ExceptionHandler(IllegalArgumentException)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, Object> badRequest(IllegalArgumentException error) {
        return [error: error.message]
    }
}

