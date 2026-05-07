package com.evoforge.agent

import org.springframework.stereotype.Component

@Component
class KnowledgeUpsertTool implements AgentTool {
    private final AgentKnowledgeService knowledgeService

    KnowledgeUpsertTool(AgentKnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService
    }

    @Override
    String name() { 'knowledge.upsert' }

    @Override
    String description() {
        'Store a reusable fact learned during execution. Use this for stable facts, not transient command output.'
    }

    @Override
    Map<String, Object> inputSchema() {
        [
            type      : 'object',
            properties: [
                key       : [type: 'string'],
                value     : [type: 'string'],
                scope     : [type: 'string'],
                tags      : [type: 'array', items: [type: 'string']],
                source    : [type: 'string'],
                confidence: [type: 'number', minimum: 0, maximum: 1]
            ],
            required  : ['key', 'value']
        ]
    }

    @Override
    AgentToolResult execute(Map<String, Object> args, AgentToolContext context) {
        AgentKnowledgeFact fact = knowledgeService.upsert(
            args.key?.toString(),
            args.value?.toString(),
            args.scope?.toString() ?: 'global',
            listArg(args.tags),
            args.source?.toString() ?: 'agent-tool',
            numberArg(args.confidence, 0.7d)
        )
        return AgentToolResult.ok(KnowledgeSearchTool.toView(fact))
    }

    private static List<String> listArg(Object value) {
        if (value instanceof Collection) {
            return value.collect { it.toString() }
        }
        return value ? value.toString().split(',').collect { it.trim() }.findAll { it } : []
    }

    private static double numberArg(Object value, double fallback) {
        return value instanceof Number ? value.doubleValue() : value ? value.toString().toDouble() : fallback
    }
}
