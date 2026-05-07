package com.evoforge.agent

class AgentToolResult {
    boolean success
    Object output
    String error
    Map<String, Object> meta = [:]

    static AgentToolResult ok(Object output, Map<String, Object> meta = [:]) {
        return new AgentToolResult(success: true, output: output, meta: meta ?: [:])
    }

    static AgentToolResult fail(String error, Map<String, Object> meta = [:]) {
        return new AgentToolResult(success: false, error: error, meta: meta ?: [:])
    }
}
