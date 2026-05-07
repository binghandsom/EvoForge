package com.evoforge.agent

import groovy.transform.ToString

import java.time.Instant

@ToString(includeNames = true)
class AgentKnowledgeFact {
    String id
    String key
    String value
    String scope = 'global'
    List<String> tags = []
    String source = 'agent'
    double confidence = 0.7d
    Instant createdAt = Instant.now()
    Instant updatedAt = Instant.now()
}
