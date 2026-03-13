package com.evoforge.model

import com.evoforge.llm.CodeModelClient
import com.evoforge.llm.LlmClient

class SkillContext {
    String input
    Map<String, Object> attributes = [:]
    LlmClient llm
    CodeModelClient codeModel
}
