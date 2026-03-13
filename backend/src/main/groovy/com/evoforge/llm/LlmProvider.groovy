package com.evoforge.llm

interface LlmProvider {
    String name()
    LlmClient client()
}
