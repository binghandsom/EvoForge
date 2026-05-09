package com.evoforge.agent

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test

import java.time.Instant

import static org.junit.jupiter.api.Assertions.*

class ProjectKnowledgeContextServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper()

    @Test
    void buildsFocusedContextInsteadOfDumpingEveryProjectFact() {
        AgentKnowledgeService knowledge = new AgentKnowledgeService(new InMemoryKnowledgeStore())
        ProjectKnowledgeContextService service = new ProjectKnowledgeContextService(knowledge, objectMapper)

        knowledge.upsert(
            'project.alpha.latest_change',
            objectMapper.writeValueAsString([
                userTask   : '把指挥台补上执行项目选择',
                status     : 'completed',
                codexOutput: '新增执行项目下拉框和 EvoForge 学习开关',
                recordedAt : '2026-05-08T00:00:00Z'
            ]),
            'project:alpha',
            ['project', 'alpha', 'latest-change', 'task-intent'],
            'codex-learning',
            0.9d
        )
        knowledge.upsert(
            'project.alpha.error.websocket',
            objectMapper.writeValueAsString([
                userTask   : '修复 RabbitMQ Web STOMP 非 SSL 连接',
                status     : 'completed',
                codexOutput: 'wss 改为 ws，避免 ERR_SSL_PROTOCOL_ERROR'
            ]),
            'project:alpha',
            ['project', 'alpha', 'error', 'network'],
            'codex-learning',
            0.86d
        )
        knowledge.upsert(
            'project.alpha.note.unrelated',
            '一条很长但与当前任务无关的普通备忘录',
            'project:alpha',
            ['project', 'alpha', 'misc'],
            'manual',
            0.6d
        )
        knowledge.upsert(
            'project.beta.latest_change',
            'beta 项目的变动不应该出现在 alpha context',
            'project:beta',
            ['project', 'beta', 'latest-change'],
            'codex-learning',
            0.95d
        )

        ProjectKnowledgeContext context = service.build(
            'alpha',
            '指挥台发送任务时报错，帮我修复执行项目相关异常',
            [contextPolicy: [maxFacts: 2, maxChars: 1200, maxCharsPerFact: 420]]
        )
        String promptBlock = context.toPromptBlock()

        assertTrue(context.hasEntries())
        assertTrue(context.entries.size() <= 2)
        assertTrue(promptBlock.contains('project.alpha.latest_change') || promptBlock.contains('project.alpha.error.websocket'))
        assertTrue(promptBlock.contains('why='))
        assertTrue(promptBlock.contains('Context policy: focused retrieval only'))
        assertFalse(promptBlock.contains('project.beta.latest_change'))
        assertFalse(promptBlock.contains('beta 项目'))
    }

    @Test
    void respectsContextBudgetAndPerFactExcerptLimit() {
        AgentKnowledgeService knowledge = new AgentKnowledgeService(new InMemoryKnowledgeStore())
        ProjectKnowledgeContextService service = new ProjectKnowledgeContextService(knowledge, objectMapper)
        String longOutput = ('输出 '.repeat(500)).trim()

        knowledge.upsert(
            'project.alpha.latest_change',
            objectMapper.writeValueAsString([
                userTask   : '继续打磨知识注入策略',
                status     : 'completed',
                codexOutput: longOutput
            ]),
            'project:alpha',
            ['project', 'alpha', 'latest-change', 'change-lineage'],
            'codex-learning',
            0.92d
        )

        ProjectKnowledgeContext context = service.build(
            'alpha',
            '继续打磨知识注入策略',
            [contextPolicy: [maxFacts: 1, maxChars: 500, maxCharsPerFact: 260]]
        )

        assertEquals(1, context.entries.size())
        assertTrue(context.entries.first().excerpt.length() <= 260)
    }

    private static class InMemoryKnowledgeStore implements AgentKnowledgeStore {
        private final Map<String, AgentKnowledgeFact> facts = new LinkedHashMap<>()

        @Override
        List<AgentKnowledgeFact> loadAll() {
            return facts.values().toList()
        }

        @Override
        Optional<AgentKnowledgeFact> findByKey(String key, String scope) {
            return Optional.ofNullable(facts["${scope ?: 'global'}:${key}".toString()])
        }

        @Override
        AgentKnowledgeFact save(AgentKnowledgeFact fact) {
            fact.updatedAt = fact.updatedAt ?: Instant.now()
            facts["${fact.scope ?: 'global'}:${fact.key}".toString()] = fact
            return fact
        }

        @Override
        void delete(String id) {
            facts.values().removeIf { it.id == id }
        }
    }
}
