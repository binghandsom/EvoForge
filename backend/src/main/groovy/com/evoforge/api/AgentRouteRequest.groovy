package com.evoforge.api

class AgentRouteRequest {
    String input
    boolean execute = false
    String llm
    String codeModel
    Map<String, Object> attributes
}
