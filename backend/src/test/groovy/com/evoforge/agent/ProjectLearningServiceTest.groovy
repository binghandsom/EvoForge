package com.evoforge.agent

import com.evoforge.device.CodexTaskResult
import com.evoforge.device.DeviceCommandMessage
import com.evoforge.device.DeviceProtocol
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class ProjectLearningServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper()

    @Test
    void recordsUserTaskCodexOutputAndLearningScopesAsChangeEpisode() {
        InMemoryKnowledgeStore store = new InMemoryKnowledgeStore()
        ProjectLearningService service = new ProjectLearningService(
            new AgentKnowledgeService(store),
            objectMapper
        )

        Map<String, Object> summary = service.recordCodexTask(
            codexCommand(
                'task-12345678',
                '把指挥台补上项目选择，并结合我发送的任务记录系统变动脉络',
                [
                    threadId        : 'thread-a',
                    projectKey      : 'alpha',
                    evoforgeLearning: [
                        enabled          : true,
                        targetProjectKey : 'evoforge',
                        sourceProjectKey : 'alpha',
                        recordChangeLineage: true,
                        learningScopes   : ['task-intent', 'change-lineage', 'codex-output']
                    ]
                ]
            ),
            CodexTaskResult.completed('已修改 command_center_page.dart，并新增学习记录。')
        )

        assertTrue(summary.enabled)
        assertEquals('alpha', summary.sourceProjectKey)
        assertEquals('evoforge', summary.targetProjectKey)

        AgentKnowledgeFact episode = store.loadAll().find {
            it.key.startsWith('project.alpha.change.') && it.key.endsWith('12345678')
        }
        assertNotNull(episode)
        assertEquals('project:alpha', episode.scope)
        assertTrue(episode.tags.contains('task-intent'))
        assertTrue(episode.tags.contains('change-lineage'))

        Map value = objectMapper.readValue(episode.value, Map)
        assertEquals('codex-task-change-episode', value.kind)
        assertEquals('thread-a', value.threadId)
        assertEquals('task-12345678', value.taskId)
        assertTrue(value.userTask.contains('记录系统变动脉络'))
        assertTrue(value.codexOutput.contains('新增学习记录'))
        assertEquals(['task-intent', 'change-lineage', 'codex-output'], value.learningScopes)

        AgentKnowledgeFact latest = store.findByKey('project.alpha.latest_change', 'project:alpha').orElse(null)
        assertNotNull(latest)
        Map latestValue = objectMapper.readValue(latest.value, Map)
        assertTrue(latestValue.userTask.contains('项目选择'))
        assertTrue(latestValue.codexOutput.contains('command_center_page.dart'))
    }

    @Test
    void linksNewChangeEpisodeToPreviousLatestChange() {
        InMemoryKnowledgeStore store = new InMemoryKnowledgeStore()
        ProjectLearningService service = new ProjectLearningService(
            new AgentKnowledgeService(store),
            objectMapper
        )

        service.recordCodexTask(
            codexCommand('task-11111111', '第一次：配置 Codex 项目选择', [projectKey: 'alpha', evoforgeLearning: true]),
            CodexTaskResult.completed('第一次完成')
        )
        service.recordCodexTask(
            codexCommand('task-22222222', '第二次：继续记录任务驱动的系统脉络', [projectKey: 'alpha', evoforgeLearning: true]),
            CodexTaskResult.completed('第二次完成')
        )

        AgentKnowledgeFact secondEpisode = store.loadAll().find {
            it.key.startsWith('project.alpha.change.') && it.key.endsWith('22222222')
        }
        assertNotNull(secondEpisode)
        Map value = objectMapper.readValue(secondEpisode.value, Map)

        assertEquals('task-11111111', value.previousChange.taskId)
        assertTrue(value.previousChange.userTask.contains('第一次'))
        assertEquals('completed', value.previousChange.status)
    }

    @Test
    void skipsRecordingWhenLearningIsDisabled() {
        InMemoryKnowledgeStore store = new InMemoryKnowledgeStore()
        ProjectLearningService service = new ProjectLearningService(
            new AgentKnowledgeService(store),
            objectMapper
        )

        Map<String, Object> summary = service.recordCodexTask(
            codexCommand('task-disabled', '普通 Codex 任务', [projectKey: 'alpha']),
            CodexTaskResult.completed('done')
        )

        assertFalse(summary.enabled)
        assertTrue(store.loadAll().isEmpty())
    }

    private static DeviceCommandMessage codexCommand(String taskId, String text, Map<String, Object> attributes) {
        return new DeviceCommandMessage(
            taskId: taskId,
            type: DeviceProtocol.TYPE_CODEX_TASK,
            text: text,
            attributes: attributes
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
}
