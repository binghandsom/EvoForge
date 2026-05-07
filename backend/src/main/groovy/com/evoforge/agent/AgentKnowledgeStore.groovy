package com.evoforge.agent

interface AgentKnowledgeStore {
    List<AgentKnowledgeFact> loadAll()
    Optional<AgentKnowledgeFact> findByKey(String key, String scope)
    AgentKnowledgeFact save(AgentKnowledgeFact fact)
    void delete(String id)
}
