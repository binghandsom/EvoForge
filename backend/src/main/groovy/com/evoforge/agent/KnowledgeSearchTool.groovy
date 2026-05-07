package com.evoforge.agent

import org.springframework.stereotype.Component

@Component
class KnowledgeSearchTool implements AgentTool {
    private final AgentKnowledgeService knowledgeService

    KnowledgeSearchTool(AgentKnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService
    }

    @Override
    String name() { 'knowledge.search' }

    @Override
    String description() {
        'Search reusable local knowledge discovered in previous runs, such as OS-specific paths, project facts, user preferences, and failed route notes.'
    }

    @Override
    Map<String, Object> inputSchema() {
        [
            type      : 'object',
            properties: [
                query: [type: 'string'],
                limit: [type: 'integer', minimum: 1, maximum: 50]
            ],
            required  : ['query']
        ]
    }

    @Override
    AgentToolResult execute(Map<String, Object> args, AgentToolContext context) {
        return AgentToolResult.ok(knowledgeService.search(args.query?.toString() ?: '', intArg(args.limit, 8)).collect { toView(it) })
    }

    private static int intArg(Object value, int fallback) {
        return value instanceof Number ? value.intValue() : value ? value.toString().toInteger() : fallback
    }

    static Map<String, Object> toView(AgentKnowledgeFact fact) {
        return [
            id        : fact.id,
            key       : fact.key,
            value     : fact.value,
            scope     : fact.scope,
            tags      : fact.tags,
            source    : fact.source,
            confidence: fact.confidence,
            updatedAt : fact.updatedAt?.toString()
        ]
    }
}
