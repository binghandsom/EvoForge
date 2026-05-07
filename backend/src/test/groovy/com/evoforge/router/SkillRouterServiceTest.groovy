package com.evoforge.router

import com.evoforge.core.EvoForgeProperties
import com.evoforge.llm.LlmClient
import com.evoforge.llm.ModelHub
import com.evoforge.model.SkillDefinition
import com.evoforge.skills.SkillRegistry
import com.evoforge.skills.SkillRuntimeEntry
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class SkillRouterServiceTest {

    @Test
    void doesNotDefaultToFirstSkillWhenNoCandidateMetadataMatches() {
        def router = routerFor([
            skill('alpha', 'Alpha', [keywords: ['alpha']]),
            skill('beta', 'Beta', [keywords: ['beta']])
        ])

        def decision = router.route('今天帮我写一段普通说明文字')

        assertEquals('NO_SKILL', decision.action)
        assertNull(decision.skillId)
        assertTrue(decision.candidates.isEmpty())
    }

    @Test
    void retrievesTopCandidatesFromShortMetadata() {
        def router = routerFor([
            skill('spreadsheet', 'Spreadsheet Skill', [
                description    : 'Analyze spreadsheet data',
                tags           : ['spreadsheet'],
                keywords       : ['csv', 'xlsx'],
                triggerExamples: ['分析 csv 数据']
            ]),
            skill('backend', 'Backend Skill', [
                tags    : ['backend'],
                keywords: ['api']
            ])
        ])

        def decision = router.route('帮我分析这个 csv 表格并给出摘要')

        assertEquals('USE_SKILL', decision.action)
        assertEquals('spreadsheet', decision.skillId)
        assertEquals('Spreadsheet Skill', decision.skillName)
        assertFalse(decision.candidates.isEmpty())
        assertEquals('spreadsheet', decision.candidates.first().skillId)
        assertTrue(decision.matched.any { it.contains('csv') || it.contains('spreadsheet') })
    }

    @Test
    void llmRouterMayChooseNoSkillFromCandidates() {
        def properties = new EvoForgeProperties()
        properties.skills.routerUseLlm = true
        def llm = new CapturingLlm('{"action":"NO_SKILL","confidence":0.91,"reason":"General writing task","matched":["writing"]}')
        def router = routerFor([
            skill('writing', 'Writing Skill', [keywords: ['文案'], tags: ['writing']])
        ], properties, llm)

        def decision = router.route('帮我改一下这段文案')

        assertEquals('NO_SKILL', decision.action)
        assertNull(decision.skillId)
        assertEquals(0.91d, decision.confidence, 0.001d)
        assertFalse(decision.candidates.isEmpty())
    }

    @Test
    void llmRouterReceivesCandidateMetadataButNotSkillCode() {
        def properties = new EvoForgeProperties()
        properties.skills.routerUseLlm = true
        def llm = new CapturingLlm('{"action":"USE_SKILL","skillId":"spreadsheet","confidence":0.88,"reason":"CSV metadata matched","matched":["csv"]}')
        def router = routerFor([
            skill('spreadsheet', 'Spreadsheet Skill', [
                description: 'Analyze spreadsheet data',
                keywords   : ['csv']
            ], 'SECRET_CODE_SHOULD_NOT_BE_ROUTED')
        ], properties, llm)

        def decision = router.route('分析 csv 文件')

        assertEquals('USE_SKILL', decision.action)
        assertEquals('spreadsheet', decision.skillId)
        assertTrue(llm.prompt.contains('Spreadsheet Skill'))
        assertTrue(llm.prompt.contains('"keywords"'))
        assertFalse(llm.prompt.contains('SECRET_CODE_SHOULD_NOT_BE_ROUTED'))
    }

    private static SkillRouterService routerFor(List<SkillDefinition> skills,
                                                EvoForgeProperties properties = new EvoForgeProperties(),
                                                LlmClient llm = new CapturingLlm('{}')) {
        return new SkillRouterService(
            new StubSkillRegistry(skills),
            new StubModelHub(llm),
            properties,
            new ObjectMapper()
        )
    }

    private static SkillDefinition skill(String id, String name, Map<String, Object> metadata, String code = '') {
        return new SkillDefinition(
            id: id,
            name: name,
            version: '0.1.0',
            language: 'groovy',
            entryClass: 'TestSkill',
            code: code,
            enabled: true,
            metadata: metadata
        )
    }

    private static class StubSkillRegistry extends SkillRegistry {
        private final List<SkillRuntimeEntry> entries

        StubSkillRegistry(List<SkillDefinition> skills) {
            super(null, null, new EvoForgeProperties())
            this.entries = skills.collect { new SkillRuntimeEntry(definition: it) }
        }

        @Override
        List<SkillRuntimeEntry> list() {
            return entries
        }
    }

    private static class StubModelHub extends ModelHub {
        private final LlmClient llm

        StubModelHub(LlmClient llm) {
            super([], [], new EvoForgeProperties(), null, null)
            this.llm = llm
        }

        @Override
        LlmClient getLlm(String name = null) {
            return llm
        }
    }

    private static class CapturingLlm implements LlmClient {
        final String response
        String prompt

        CapturingLlm(String response) {
            this.response = response
        }

        @Override
        String chat(String prompt) {
            return chat(prompt, [:])
        }

        @Override
        String chat(String prompt, Map<String, Object> options) {
            this.prompt = prompt
            return response
        }
    }
}
