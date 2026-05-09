package com.evoforge.learning

import com.evoforge.agent.AgentKnowledgeFact
import com.evoforge.agent.AgentKnowledgeService
import com.evoforge.agent.AgentKnowledgeStore
import com.evoforge.audit.SkillAuditService
import com.evoforge.audit.SkillAuditStore
import com.evoforge.audit.SkillEvent
import com.evoforge.audit.SkillEventType
import com.evoforge.core.EvoForgeProperties
import com.evoforge.llm.ModelProviderConfig
import com.evoforge.llm.ModelProviderConfigService
import com.evoforge.llm.ModelProviderConfigStore
import com.evoforge.model.SkillDefinition
import com.evoforge.store.SkillStore
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test

import java.time.Instant

import static org.junit.jupiter.api.Assertions.*

class SelfLearningDashboardServiceTest {

    @Test
    void startupCreatesBoundedAutonomousLearningSideQuest() {
        Fixture fixture = new Fixture()
        fixture.properties.selfLearning.maxRecentActivities = 2

        Map<String, Object> result = fixture.service.startAutonomousLearningSideQuest()
        assertTrue(result.started)

        assertTrue(fixture.knowledgeStore.findByKey(
            SelfLearningDashboardService.STARTUP_SIDE_QUEST_KEY,
            'global'
        ).isPresent())
        assertTrue(fixture.knowledgeStore.findByKey(
            SelfLearningDashboardService.INTERFACE_POLICY_KEY,
            'global'
        ).isPresent())

        Map<String, Object> dashboard = fixture.service.dashboard(20)
        assertEquals(2, (dashboard.activityRetention as Map).backendMaxItems)
        assertTrue((dashboard.activities as List).size() <= 2)
        assertFalse((dashboard.activeSideQuests as List).isEmpty())
        assertEquals(30, (dashboard.learningVelocity as Map).targetImprovementCycleMinutes)
        assertEquals(4, (dashboard.resourceUtilization as Map).maxParallelLearningTasks)
        assertEquals('ChatGPT-like reasoning model with multimodal generation', (dashboard.modelEvolution as Map).target)
        assertTrue(((dashboard.modelEvolution as Map).boundary as String).contains('不是模型本体'))
        assertTrue((dashboard.deprecatedInterfaces as List).any {
            it.id == 'unbounded-web-crawl-as-progress'
        })
    }

    @Test
    void dashboardPromotesOnlyEffectiveImprovementsToMilestones() {
        Fixture fixture = new Fixture()
        SkillDefinition skill = new SkillDefinition(
            id: 'wps-skill',
            name: 'WPS 云文档编辑',
            code: 'class WpsSkill {}',
            enabled: true
        )
        fixture.skillStore.save(skill)
        fixture.auditService.record(SkillEventType.PROPOSED, 'draft-skill', '待确认 Skill', [prompt: '还未验证'])
        fixture.auditService.record(SkillEventType.ACTIVATED, skill, [:])
        fixture.modelStore.save(new ModelProviderConfig(
            id: 'gpt-ok',
            name: 'GPT OK',
            providerType: 'openai',
            modelName: 'gpt-4.1',
            apiKey: 'sk-test',
            enabled: true,
            supportsLlm: true,
            supportsCodeModel: true,
            metadata: [supportsImageGeneration: true],
            updatedAt: Instant.parse('2026-05-09T00:00:00Z')
        ))
        fixture.modelStore.save(new ModelProviderConfig(
            id: 'old-api',
            name: 'Old API',
            providerType: 'openai-compatible',
            modelName: 'legacy',
            enabled: false,
            metadata: [status: 'deprecated', reason: '历史接口不再推荐']
        ))
        fixture.knowledgeService.upsert(
            'project.evoforge.change.1',
            fixture.objectMapper.writeValueAsString([
                kind     : 'codex-task-change-episode',
                status   : 'completed',
                userTask : '新增自学习页面',
                codexOutput: '测试通过'
            ]),
            'project:evoforge',
            ['project', 'latest-change'],
            'codex-learning',
            0.8d
        )
        fixture.knowledgeService.upsert(
            'research.page.1',
            fixture.objectMapper.writeValueAsString([
                kind: 'web-page-visited',
                url : 'https://example.com/research',
                title: '只浏览网页'
            ]),
            'global',
            ['web-research'],
            'browser',
            0.6d
        )

        Map<String, Object> dashboard = fixture.service.dashboard(80)
        List milestones = dashboard.milestones as List
        List interfaces = dashboard.interfaceExamples as List
        List activities = dashboard.activities as List

        assertTrue(milestones.any { it.category == 'skill' && it.status == 'OK' })
        assertTrue(milestones.any { it.category == 'model-interface' && it.status == 'OK' })
        assertTrue(milestones.any { it.category == 'validated-change' && it.status == 'OK' })
        assertFalse(milestones.any { it.title?.toString()?.contains('待确认') })
        assertTrue(interfaces.any { it.endpoint == '/api/models/llm/chat' && it.status == 'OK' })
        assertTrue((dashboard.deprecatedInterfaces as List).any { it.id == 'model-config:old-api' })
        assertTrue(activities.any { it.type == 'web-research' })
        assertTrue((dashboard.learningVelocity as Map).recentMilestoneCount >= 1)
        assertTrue((dashboard.resourceUtilization as Map).networkLearningEnabled)
        Map modelEvolution = dashboard.modelEvolution as Map
        assertEquals(1, (modelEvolution.metrics as Map).multimodalGenerationBackends)
        assertTrue((modelEvolution.tracks as List).any {
            it.id == 'multimodal-generation' && it.status == 'interface-ready'
        })
    }

    private static class Fixture {
        final ObjectMapper objectMapper = new ObjectMapper()
        final EvoForgeProperties properties = new EvoForgeProperties()
        final InMemoryKnowledgeStore knowledgeStore = new InMemoryKnowledgeStore()
        final AgentKnowledgeService knowledgeService = new AgentKnowledgeService(knowledgeStore)
        final InMemorySkillStore skillStore = new InMemorySkillStore()
        final InMemorySkillAuditStore auditStore = new InMemorySkillAuditStore()
        final SkillAuditService auditService = new SkillAuditService(auditStore)
        final InMemoryModelProviderConfigStore modelStore = new InMemoryModelProviderConfigStore()
        final ModelProviderConfigService modelProviderConfigService = new ModelProviderConfigService(modelStore)
        final SelfLearningDashboardService service = new SelfLearningDashboardService(
            knowledgeService,
            skillStore,
            auditService,
            modelProviderConfigService,
            properties,
            objectMapper
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
            skills[skill.id] = skill
            return skill
        }

        @Override
        void delete(String id) {
            skills.remove(id)
        }
    }

    private static class InMemorySkillAuditStore implements SkillAuditStore {
        private final List<SkillEvent> events = []

        @Override
        List<SkillEvent> loadAll() {
            return events.toList()
        }

        @Override
        void append(SkillEvent event) {
            events << event
        }

        @Override
        List<SkillEvent> listForSkill(String skillId) {
            return events.findAll { it.skillId == skillId }
        }

        @Override
        void clear() {
            events.clear()
        }
    }

    private static class InMemoryModelProviderConfigStore implements ModelProviderConfigStore {
        private final Map<String, ModelProviderConfig> configs = new LinkedHashMap<>()

        @Override
        List<ModelProviderConfig> loadAll() {
            return configs.values().toList()
        }

        @Override
        Optional<ModelProviderConfig> findById(String id) {
            return Optional.ofNullable(configs[id])
        }

        @Override
        ModelProviderConfig save(ModelProviderConfig config) {
            configs[config.id] = config
            return config
        }

        @Override
        void delete(String id) {
            configs.remove(id)
        }
    }
}
