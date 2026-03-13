package com.evoforge.llm

interface CodeModelClient {
    String generateSkill(String prompt, Map<String, Object> options = [:])
}
