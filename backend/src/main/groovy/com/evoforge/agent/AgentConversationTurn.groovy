package com.evoforge.agent

import groovy.transform.ToString

import java.time.Instant

@ToString(includeNames = true)
class AgentConversationTurn {
    String id
    String threadId
    String role
    String content
    Map<String, Object> metadata = [:]
    Instant createdAt = Instant.now()
}
