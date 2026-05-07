package com.evoforge.llm

interface LlmClient {
    String chat(String prompt)
    String chat(String prompt, Map<String, Object> options)
}
