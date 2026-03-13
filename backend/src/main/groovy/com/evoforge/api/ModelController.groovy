package com.evoforge.api

import com.evoforge.llm.ModelHub
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping('/api/models')
class ModelController {
    private final ModelHub modelHub

    ModelController(ModelHub modelHub) {
        this.modelHub = modelHub
    }

    @GetMapping('llm')
    List<String> listLlms() {
        return modelHub.listLlmProviders()
    }

    @GetMapping('code')
    List<String> listCodeModels() {
        return modelHub.listCodeProviders()
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
}
