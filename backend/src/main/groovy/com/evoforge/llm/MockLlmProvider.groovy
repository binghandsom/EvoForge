package com.evoforge.llm

import org.springframework.stereotype.Component

@Component
class MockLlmProvider implements LlmProvider {
    @Override
    String name() {
        return 'mock-llm'
    }

    @Override
    LlmClient client() {
        return new LlmClient() {
            @Override
            String chat(String prompt, Map<String, Object> options = [:]) {
                return "[mock-llm] ${prompt}".toString()
            }
        }
    }
}
