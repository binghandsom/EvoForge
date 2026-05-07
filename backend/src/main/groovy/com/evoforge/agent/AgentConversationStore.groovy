package com.evoforge.agent

import java.time.Instant

interface AgentConversationStore {
    AgentConversationThread upsertThread(AgentConversationThread thread)
    Optional<AgentConversationThread> findThread(String threadId)
    List<AgentConversationThread> listThreads(int limit)
    void touchThread(String threadId, String role, String content, Instant updatedAt, String titleCandidate)
    AgentConversationTurn append(AgentConversationTurn turn)
    List<AgentConversationTurn> listRecent(String threadId, int limit)
}
