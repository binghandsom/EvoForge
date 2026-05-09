package com.evoforge.codex

import com.evoforge.core.EvoForgeProperties
import com.evoforge.device.DeviceCommandMessage
import com.evoforge.device.DeviceEventPublisher
import com.evoforge.device.DeviceEventSignatureService
import com.evoforge.device.DeviceProtocol
import com.evoforge.device.DeviceTaskEvent
import com.evoforge.device.DeviceTaskEventPage
import com.evoforge.device.DeviceTaskEventStore
import org.junit.jupiter.api.Test

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import static org.junit.jupiter.api.Assertions.*

class CodexQuestionBridgeServiceTest {

    @Test
    void askPublishesMobileQuestionAndWaitsForHumanResponse() {
        EvoForgeProperties properties = new EvoForgeProperties()
        properties.deviceAgent.userId = 'user-1'
        properties.deviceAgent.deviceId = 'pc-1'
        properties.codexTask.questionTimeoutSeconds = 5
        InMemoryEventStore store = new InMemoryEventStore()
        CodexQuestionBridgeService service = new CodexQuestionBridgeService(
            new CapturingPublisher(store, properties),
            properties
        )

        def executor = Executors.newSingleThreadExecutor()
        def future = executor.submit({
            service.ask([
                questionId: 'question-1',
                taskId    : 'task-1',
                projectKey: 'evoforge',
                question  : '选择哪条路线？',
                options   : ['快测', '全测']
            ])
        } as java.util.concurrent.Callable<Map<String, Object>>)

        waitUntil { !store.listForTask('task-1').isEmpty() }
        DeviceTaskEvent questionEvent = store.listForTask('task-1').first()
        assertEquals(DeviceProtocol.STATUS_NEEDS_INPUT, questionEvent.status)
        assertEquals('question-1', questionEvent.payload.codexQuestion.questionId)

        Map<String, Object> delivered = service.answer(new DeviceCommandMessage(
            taskId: 'task-1',
            type: DeviceProtocol.TYPE_HUMAN_RESPONSE,
            text: '全测',
            attributes: [
                codexQuestionAnswer: [
                    questionId: 'question-1',
                    taskId    : 'task-1',
                    answer    : '全测',
                    actor     : 'mobile'
                ]
            ]
        ))

        assertTrue(delivered.delivered)
        Map<String, Object> answer = future.get(2, TimeUnit.SECONDS)
        assertTrue(answer.answered)
        assertEquals('全测', answer.answer)
        assertTrue(store.listForTask('task-1').any { it.status == DeviceProtocol.STATUS_INPUT_RECEIVED })
        executor.shutdownNow()
    }

    @Test
    void askReturnsTimeoutWhenNoMobileResponseArrives() {
        EvoForgeProperties properties = new EvoForgeProperties()
        properties.codexTask.questionTimeoutSeconds = 1
        InMemoryEventStore store = new InMemoryEventStore()
        CodexQuestionBridgeService service = new CodexQuestionBridgeService(
            new CapturingPublisher(store, properties),
            properties
        )

        Map<String, Object> response = service.ask([
            questionId: 'question-timeout',
            taskId    : 'task-timeout',
            question  : '还在吗？',
            timeoutSeconds: 1
        ])

        assertFalse(response.answered)
        assertTrue(response.timedOut)
        assertTrue(store.listForTask('task-timeout').any { it.status == DeviceProtocol.STATUS_FAILED })
    }

    @Test
    void askCanPublishAgentSkillProposalForMobileConfirmation() {
        EvoForgeProperties properties = new EvoForgeProperties()
        properties.codexTask.questionTimeoutSeconds = 1
        InMemoryEventStore store = new InMemoryEventStore()
        CodexQuestionBridgeService service = new CodexQuestionBridgeService(
            new CapturingPublisher(store, properties),
            properties
        )

        service.ask([
            questionId: 'skill-question',
            taskId    : 'task-skill',
            requester : 'agent-skill-proposal',
            question  : '确认创建这个 skill 吗？',
            skillProposal: [
                name       : 'ScriptToVideoPromptSkill',
                description: '把剧本文本转换为视频提示词'
            ],
            timeoutSeconds: 1
        ])

        DeviceTaskEvent event = store.listForTask('task-skill').find { it.status == DeviceProtocol.STATUS_NEEDS_INPUT }
        assertNotNull(event)
        assertEquals('agent-skill-proposal', event.payload.codexQuestion.requester)
        assertEquals('ScriptToVideoPromptSkill', event.payload.codexQuestion.skillProposal.name)
        assertTrue(event.message.contains('EvoForge skill proposal'))
    }

    private static void waitUntil(Closure<Boolean> predicate) {
        long deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline) {
            if (predicate.call()) return
            Thread.sleep(20)
        }
        fail('condition not met before timeout')
    }

    private static class CapturingPublisher extends DeviceEventPublisher {
        CapturingPublisher(DeviceTaskEventStore store, EvoForgeProperties properties) {
            super(null, store, properties, new DeviceEventSignatureService(properties))
        }

        @Override
        DeviceTaskEvent publish(DeviceTaskEvent event) {
            event.userId = event.userId ?: properties.deviceAgent.userId
            event.deviceId = event.deviceId ?: properties.deviceAgent.deviceId
            return eventStore.append(event)
        }
    }

    private static class InMemoryEventStore implements DeviceTaskEventStore {
        private final List<DeviceTaskEvent> events = new CopyOnWriteArrayList<>()

        @Override
        DeviceTaskEvent append(DeviceTaskEvent event) {
            event.eventId = event.eventId ?: UUID.randomUUID().toString()
            event.createdAt = event.createdAt ?: Instant.now()
            events << event
            return event
        }

        @Override
        List<DeviceTaskEvent> listForTask(String taskId) {
            return events.findAll { it.taskId == taskId }.sort { it.createdAt }
        }

        @Override
        DeviceTaskEventPage listForTaskPage(String taskId, int limit, String beforeCursor, String afterCursor) {
            List<DeviceTaskEvent> ordered = listForTask(taskId)
            int safeLimit = Math.max(1, Math.min(limit, 200))
            return DeviceTaskEventPage.fromItems(ordered.takeRight(safeLimit), safeLimit, ordered.size() > safeLimit, false)
        }

        @Override
        List listRecentTasks(int limit) {
            return []
        }
    }
}
