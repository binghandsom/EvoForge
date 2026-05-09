package com.evoforge.codex

import com.evoforge.agent.AgentKnowledgeFact
import com.evoforge.agent.AgentKnowledgeService
import com.evoforge.agent.AgentKnowledgeStore
import com.evoforge.agent.ProjectKnowledgeContextService
import com.evoforge.model.SkillDefinition
import com.evoforge.model.SkillStatus
import com.evoforge.store.SkillStore
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test

import java.time.Instant

import static org.junit.jupiter.api.Assertions.*

class CodexBridgeServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper()

    @Test
    void exposesManifestWithPullBasedContextAndSkillEndpoints() {
        CodexBridgeService service = newService()

        Map<String, Object> manifest = service.manifest('alpha', 'http://localhost:18080/')

        assertEquals('EvoForge Codex Bridge', manifest.name)
        assertTrue(manifest.retrieval.endpoint.toString().endsWith('/api/codex/bridge/query'))
        assertEquals('focused-hybrid', manifest.retrieval.mode)
        assertFalse(manifest.retrieval.vectorReady)
        assertTrue(manifest.skillCatalog.execute.toString().contains('/api/skills/{id}/execute'))
        assertTrue((manifest.usageRules as List).any { it.toString().contains('small') || it.toString().contains('task-specific') })
    }

    @Test
    void queryReturnsFocusedProjectContextAndRelevantSkillCards() {
        InMemoryKnowledgeStore knowledgeStore = new InMemoryKnowledgeStore()
        AgentKnowledgeService knowledge = new AgentKnowledgeService(knowledgeStore)
        knowledge.upsert(
            'project.alpha.latest_change',
            objectMapper.writeValueAsString([
                userTask   : '修复 WebSocket 非 SSL 连接',
                status     : 'completed',
                codexOutput: 'RabbitMQ Web STOMP 使用 ws 连接'
            ]),
            'project:alpha',
            ['project', 'alpha', 'latest-change', 'error'],
            'codex-learning',
            0.9d
        )
        InMemorySkillStore skillStore = new InMemorySkillStore()
        skillStore.save(new SkillDefinition(
            id: 'image-screen',
            name: 'Image Screen',
            version: '1.0.0',
            language: 'groovy',
            entryClass: 'com.evoforge.dynamic.ImageScreenSkill',
            enabled: true,
            status: SkillStatus.ACTIVE,
            metadata: [
                description    : 'Analyze and triage local images',
                keywords       : ['image', '图片', 'screen'],
                triggerExamples: ['图片初筛']
            ]
        ))
        CodexBridgeService service = new CodexBridgeService(
            new ProjectKnowledgeContextService(knowledge, objectMapper),
            skillStore
        )

        Map<String, Object> response = service.query([
            projectKey    : 'alpha',
            query         : '图片初筛前先看一下 WebSocket 报错相关项目记忆',
            includeContext: true,
            includeSkills : true,
            skillLimit    : 3,
            contextPolicy : [maxFacts: 2, maxChars: 1200, maxCharsPerFact: 400]
        ], 'http://localhost:18080')

        assertEquals('evoforge-codex-bridge', response.bridge)
        assertEquals('focused-hybrid', response.retrievalMode)
        assertTrue((response.context.count as int) > 0)
        assertTrue(response.context.promptBlock.toString().contains('project.alpha.latest_change'))
        assertEquals('image-screen', (response.skills as List).first().id)
        assertTrue((response.skills as List).first().execute.endpoint.toString().contains('/api/skills/image-screen/execute'))
    }

    private CodexBridgeService newService() {
        AgentKnowledgeService knowledge = new AgentKnowledgeService(new InMemoryKnowledgeStore())
        return new CodexBridgeService(
            new ProjectKnowledgeContextService(knowledge, objectMapper),
            new InMemorySkillStore()
        )
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

    private static class InMemorySkillStore implements SkillStore {
        private final Map<String, SkillDefinition> skills = new LinkedHashMap<>()

        @Override
        List<SkillDefinition> loadAll() {
            return skills.values().toList()
        }

        @Override
        List<SkillDefinition> loadAllSummaries() {
            return loadAll()
        }

        @Override
        Optional<SkillDefinition> findById(String id) {
            return Optional.ofNullable(skills[id])
        }

        @Override
        Optional<SkillDefinition> findSummaryById(String id) {
            return findById(id)
        }

        @Override
        SkillDefinition save(SkillDefinition skill) {
            skill.id = skill.id ?: UUID.randomUUID().toString()
            skill.updatedAt = skill.updatedAt ?: Instant.now()
            skills[skill.id] = skill
            return skill
        }

        @Override
        void delete(String id) {
            skills.remove(id)
        }
    }
}
