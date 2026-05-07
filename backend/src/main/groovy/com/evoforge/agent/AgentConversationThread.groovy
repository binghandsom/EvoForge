package com.evoforge.agent

import groovy.transform.ToString

import java.time.Instant

@ToString(includeNames = true)
class AgentConversationThread {
    String threadId
    String title = '新对话'
    String summary = ''
    Map<String, Object> metadata = [:]
    int turnCount = 0
    String lastRole = ''
    String lastContent = ''
    Instant createdAt = Instant.now()
    Instant updatedAt = Instant.now()
}
