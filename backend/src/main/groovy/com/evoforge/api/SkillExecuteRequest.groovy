package com.evoforge.api

class SkillExecuteRequest {
    String input
    Map<String, Object> attributes
    String llm
    String codeModel
    boolean evaluate = false
}
