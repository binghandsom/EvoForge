package com.evoforge.agent

interface AgentTool {
    String name()
    String description()
    Map<String, Object> inputSchema()
    AgentToolResult execute(Map<String, Object> args, AgentToolContext context)
}
