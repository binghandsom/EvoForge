package com.evoforge.agent

import org.springframework.stereotype.Component

@Component
class AgentToolRegistry {
    private final Map<String, AgentTool> tools

    AgentToolRegistry(List<AgentTool> tools) {
        this.tools = tools.collectEntries { [(it.name()): it] }
    }

    List<Map<String, Object>> descriptors() {
        return tools.values()
            .sort { it.name() }
            .collect {
                [
                    name       : it.name(),
                    description: it.description(),
                    inputSchema: it.inputSchema()
                ]
            }
    }

    AgentToolResult execute(String name, Map<String, Object> args, AgentToolContext context) {
        AgentTool tool = tools[name]
        if (!tool) {
            return AgentToolResult.fail("Unknown tool: ${name}".toString(), [availableTools: tools.keySet().sort()])
        }
        try {
            return tool.execute(args ?: [:], context)
        } catch (Exception ex) {
            return AgentToolResult.fail(ex.message ?: ex.class.simpleName, [errorType: ex.class.name])
        }
    }
}
