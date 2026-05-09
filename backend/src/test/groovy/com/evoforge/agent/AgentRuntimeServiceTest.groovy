package com.evoforge.agent

import com.evoforge.core.EvoForgeProperties
import com.evoforge.codex.CodexQuestionBridgeService
import com.evoforge.device.DeviceEventPublisher
import com.evoforge.device.DeviceEventSignatureService
import com.evoforge.device.InMemoryDeviceTaskEventStore
import com.evoforge.device.NoopDeviceEventPublisher
import com.evoforge.llm.LlmClient
import com.evoforge.llm.ModelHub
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test

import java.time.Instant

import static org.junit.jupiter.api.Assertions.*

class AgentRuntimeServiceTest {

    @Test
    void executesPlannerSelectedToolAndLearnsReusableFacts() {
        AgentKnowledgeService knowledge = new AgentKnowledgeService(new InMemoryKnowledgeStore())
        AgentConversationMemoryService conversations = new AgentConversationMemoryService(new InMemoryConversationStore())
        QueueLlm llm = new QueueLlm([
            '''
            {
              "thought": "Need local system facts first.",
              "routes": [
                {"id":"A","status":"active","rationale":"Use built-in system tool","next":"call system.info"},
                {"id":"B","status":"candidate","rationale":"Fallback to shell command","next":"call shell.run uname"}
              ],
              "selectedRouteId": "A",
              "action": {"tool":"system.info","args":{}},
              "finalAnswer": null,
              "knowledgeWrites": []
            }
            ''',
            '''
            {
              "thought": "System facts are available.",
              "routes": [
                {"id":"A","status":"done","rationale":"system.info returned local facts","next":"answer user"}
              ],
              "selectedRouteId": "A",
              "action": null,
              "finalAnswer": "系统信息已经确认，可以继续解析本机路径。",
              "knowledgeWrites": [
                {"key":"planner.test.fact","value":"learned","scope":"global","tags":["test"],"confidence":0.9,"source":"unit-test"}
              ]
            }
            '''
        ])
        AgentRuntimeService runtime = new AgentRuntimeService(
            new StubModelHub(llm),
            new AgentToolRegistry([
                new SystemInfoTool(),
                new KnowledgeSearchTool(knowledge),
                new KnowledgeUpsertTool(knowledge)
            ]),
            knowledge,
            conversations,
            testEventPublisher(),
            new EvoForgeProperties(),
            new ObjectMapper(),
            null,
            null,
            null
        )

        AgentRunResult result = runtime.run('确认当前系统信息', [:])

        assertTrue(result.success)
        assertEquals('系统信息已经确认，可以继续解析本机路径。', result.output)
        assertEquals('system.info', result.observations.first().tool)
        assertTrue(result.routes.any { it.id == 'A' })
        assertEquals('default', result.threadId)
        assertTrue(knowledge.search('local.os.family', 10).any { it.key == 'local.os.family' })
        assertTrue(knowledge.search('planner.test.fact', 10).any { it.value == 'learned' })
        assertEquals(['user', 'assistant'], conversations.recent('default', 10).collect { it.role })
    }

    @Test
    void includesSameThreadMemoryWhenPlanningFollowUp() {
        AgentKnowledgeService knowledge = new AgentKnowledgeService(new InMemoryKnowledgeStore())
        AgentConversationMemoryService conversations = new AgentConversationMemoryService(new InMemoryConversationStore())
        conversations.append(
            'thread-1',
            'user',
            '之前说过：下载图片初筛不能硬写死下载目录，要先识别当前系统，再动态找可能路径。',
            [:]
        )
        QueueLlm llm = new QueueLlm([
            '''
            {
              "thought": "Follow-up should use the prior thread requirement.",
              "routes": [],
              "selectedRouteId": null,
              "action": null,
              "finalAnswer": "会沿用同一对话的上下文，不会把路径写死。",
              "knowledgeWrites": []
            }
            '''
        ])
        AgentRuntimeService runtime = new AgentRuntimeService(
            new StubModelHub(llm),
            new AgentToolRegistry([
                new KnowledgeSearchTool(knowledge),
                new KnowledgeUpsertTool(knowledge)
            ]),
            knowledge,
            conversations,
            testEventPublisher(),
            new EvoForgeProperties(),
            new ObjectMapper(),
            null,
            null,
            null
        )

        AgentRunResult result = runtime.run('那这个能力继续按刚才那个思路做', [threadId: 'thread-1'])

        assertTrue(result.success)
        assertEquals('thread-1', result.threadId)
        assertTrue(llm.prompts.first().contains('不能硬写死下载目录'))
        assertEquals(['user', 'user', 'assistant'], conversations.recent('thread-1', 10).collect { it.role })
    }

    @Test
    void explicitSkillRequestOffersConfirmationAfterBlockedToolEvidence() {
        AgentKnowledgeService knowledge = new AgentKnowledgeService(new InMemoryKnowledgeStore())
        AgentConversationMemoryService conversations = new AgentConversationMemoryService(new InMemoryConversationStore())
        QueueLlm llm = new QueueLlm([
            '''
            {
              "thought": "First verify the previous cache path.",
              "routes": [
                {"id":"A","status":"active","rationale":"Check old cache path","next":"resolve path"},
                {"id":"D","status":"candidate","rationale":"If local cache cannot be located, form a reusable Skill.","next":"形成 WPS 云文档定位与编辑 Skill"}
              ],
              "selectedRouteId": "A",
              "action": {"tool":"path.resolve","args":{"path":"/tmp/evoforge-definitely-missing.docx"}},
              "finalAnswer": null,
              "knowledgeWrites": []
            }
            ''',
            '''
            {
              "thought": "The cache path is missing, so ask for skill confirmation.",
              "routes": [
                {"id":"A","status":"blocked","rationale":"The old cache path is missing","next":"preserve failure"},
                {"id":"D","status":"active","rationale":"A reusable Skill is needed.","next":"ask user to confirm Skill creation"}
              ],
              "selectedRouteId": "D",
              "action": null,
              "finalAnswer": "本地缓存路径不存在，无法继续直接编辑。",
              "knowledgeWrites": []
            }
            '''
        ])
        AnsweringQuestionBridgeService questionBridge = new AnsweringQuestionBridgeService()
        AgentRuntimeService runtime = new AgentRuntimeService(
            new StubModelHub(llm),
            new AgentToolRegistry([
                new PathResolveTool(),
                new KnowledgeSearchTool(knowledge),
                new KnowledgeUpsertTool(knowledge)
            ]),
            knowledge,
            conversations,
            testEventPublisher(),
            new EvoForgeProperties(),
            new ObjectMapper(),
            questionBridge,
            null,
            null
        )

        AgentRunResult result = runtime.run(
            '我想让 EvoForge 自主形成 skill 解决 WPS 云文档缓存丢失问题，只是形成前需要确认。',
            [taskId: 'task-skill', userId: 'user-1', deviceId: 'pc-1']
        )

        assertEquals(1, questionBridge.requests.size())
        assertEquals('agent-skill-proposal', questionBridge.requests.first().requester)
        assertEquals('skill-service-unavailable', result.stopReason)
        assertTrue(result.output.contains('skill 创建服务'))
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

    private static DeviceEventPublisher testEventPublisher() {
        EvoForgeProperties properties = new EvoForgeProperties()
        return new NoopDeviceEventPublisher(
            new InMemoryDeviceTaskEventStore(),
            properties,
            new DeviceEventSignatureService(properties)
        )
    }

    private static class AnsweringQuestionBridgeService extends CodexQuestionBridgeService {
        final List<Map<String, Object>> requests = []

        AnsweringQuestionBridgeService() {
            super(
                new NoopDeviceEventPublisher(
                    new InMemoryDeviceTaskEventStore(),
                    new EvoForgeProperties(),
                    new DeviceEventSignatureService(new EvoForgeProperties())
                ),
                new EvoForgeProperties()
            )
        }

        @Override
        Map<String, Object> ask(Map request) {
            requests << (request as Map<String, Object>)
            return [
                answered: true,
                answer  : '确认创建并继续',
                actor   : 'unit-test'
            ] as Map<String, Object>
        }
    }

    private static class QueueLlm implements LlmClient {
        final Queue<String> responses = new ArrayDeque<>()
        final List<String> prompts = []

        QueueLlm(List<String> responses) {
            this.responses.addAll(responses)
        }

        @Override
        String chat(String prompt) {
            return chat(prompt, [:])
        }

        @Override
        String chat(String prompt, Map<String, Object> options) {
            prompts << prompt
            return responses.poll() ?: '{"finalAnswer":"done"}'
        }
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

    private static class InMemoryConversationStore implements AgentConversationStore {
        private final Map<String, AgentConversationThread> threads = [:]
        private final List<AgentConversationTurn> turns = []

        @Override
        AgentConversationThread upsertThread(AgentConversationThread thread) {
            thread.threadId = thread.threadId ?: UUID.randomUUID().toString()
            threads[thread.threadId] = thread
            return thread
        }

        @Override
        Optional<AgentConversationThread> findThread(String threadId) {
            return Optional.ofNullable(threads[threadId])
        }

        @Override
        List<AgentConversationThread> listThreads(int limit) {
            return threads.values()
                .sort { a, b -> b.updatedAt <=> a.updatedAt }
                .take(Math.max(1, Math.min(limit, 200)))
        }

        @Override
        void touchThread(String threadId, String role, String content, Instant updatedAt, String titleCandidate) {
            AgentConversationThread thread = threads[threadId] ?: new AgentConversationThread(threadId: threadId)
            if ((!thread.title || thread.title == '新对话') && titleCandidate) {
                thread.title = titleCandidate
            }
            thread.turnCount = thread.turnCount + 1
            thread.lastRole = role
            thread.lastContent = content
            thread.updatedAt = updatedAt ?: Instant.now()
            threads[threadId] = thread
        }

        @Override
        AgentConversationTurn append(AgentConversationTurn turn) {
            turn.id = turn.id ?: UUID.randomUUID().toString()
            turn.createdAt = turn.createdAt ?: Instant.now()
            turns << turn
            return turn
        }

        @Override
        List<AgentConversationTurn> listRecent(String threadId, int limit) {
            return turns
                .findAll { it.threadId == threadId }
                .sort { a, b -> a.createdAt <=> b.createdAt }
                .takeRight(Math.max(1, Math.min(limit, 100)))
        }
    }
}
