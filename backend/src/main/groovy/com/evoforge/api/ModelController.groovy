package com.evoforge.api

import com.evoforge.llm.ModelHub
import com.evoforge.llm.ModelProviderConfigService
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping('/api/models')
class ModelController {
    private final ModelHub modelHub
    private final ModelProviderConfigService configService

    ModelController(ModelHub modelHub, ModelProviderConfigService configService) {
        this.modelHub = modelHub
        this.configService = configService
    }

    @GetMapping('llm')
    List<String> listLlms() {
        return modelHub.listLlmProviders()
    }

    @GetMapping('code')
    List<String> listCodeModels() {
        return modelHub.listCodeProviders()
    }

    @GetMapping('configs')
    List<Map<String, Object>> listConfigs() {
        return configService.list().collect { ModelProviderConfigService.toView(it) }
    }

    @PostMapping('configs')
    Map<String, Object> createConfig(@RequestBody Map<String, Object> request) {
        return ModelProviderConfigService.toView(configService.upsert(request))
    }

    @PutMapping('configs/{id}')
    Map<String, Object> updateConfig(@PathVariable('id') String id, @RequestBody Map<String, Object> request) {
        Map<String, Object> payload = new LinkedHashMap<>(request ?: [:])
        payload.id = id
        return ModelProviderConfigService.toView(configService.upsert(payload))
    }

    @DeleteMapping('configs/{id}')
    Map<String, Object> deleteConfig(@PathVariable('id') String id) {
        if (!id) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, 'id is required')
        }
        configService.delete(id)
        return [deleted: true]
    }

    @PostMapping('llm/chat')
    Map<String, Object> chat(@RequestBody Map<String, Object> request) {
        String prompt = request.prompt?.toString() ?: ''
        String provider = request.provider?.toString()
        def reply = modelHub.getLlm(provider).chat(prompt, request)
        return [output: reply]
    }

    @PostMapping('code/generate')
    Map<String, Object> generate(@RequestBody Map<String, Object> request) {
        String prompt = request.prompt?.toString() ?: ''
        String provider = request.provider?.toString()
        def code = modelHub.getCodeModel(provider).generateSkill(prompt, request)
        return [code: code]
    }

    @ExceptionHandler(IllegalArgumentException)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, Object> badRequest(IllegalArgumentException error) {
        return [error: error.message]
    }
}
