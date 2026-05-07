package com.evoforge.agent

import org.springframework.stereotype.Service

import java.time.Instant

@Service
class AgentKnowledgeService {
    private final AgentKnowledgeStore store

    AgentKnowledgeService(AgentKnowledgeStore store) {
        this.store = store
    }

    Optional<AgentKnowledgeFact> findByKey(String key, String scope = 'global') {
        String normalizedKey = required(key, 'key')
        String normalizedScope = text(scope) ?: 'global'
        return store.findByKey(normalizedKey, normalizedScope)
    }

    List<AgentKnowledgeFact> search(String query, int limit = 8) {
        String normalized = (query ?: '').toLowerCase(Locale.ROOT).trim()
        int safeLimit = Math.max(1, Math.min(limit, 50))
        if (!normalized) {
            return store.loadAll()
                .sort { a, b -> b.updatedAt <=> a.updatedAt }
                .take(safeLimit)
        }
        return store.loadAll()
            .collect { fact -> [fact: fact, score: score(fact, normalized)] }
            .findAll { it.score > 0 }
            .sort { a, b -> b.score <=> a.score ?: b.fact.updatedAt <=> a.fact.updatedAt }
            .collect { it.fact as AgentKnowledgeFact }
            .take(safeLimit)
    }

    AgentKnowledgeFact upsert(String key,
                              String value,
                              String scope = 'global',
                              List<String> tags = [],
                              String source = 'agent',
                              double confidence = 0.7d) {
        String normalizedKey = required(key, 'key')
        String normalizedScope = text(scope) ?: 'global'
        AgentKnowledgeFact fact = store.findByKey(normalizedKey, normalizedScope).orElseGet {
            new AgentKnowledgeFact(
                id: UUID.randomUUID().toString(),
                key: normalizedKey,
                scope: normalizedScope,
                createdAt: Instant.now()
            )
        }
        fact.value = required(value, 'value')
        fact.tags = (tags ?: []).findAll { it != null && it.toString().trim() }.collect { it.toString().trim() }.unique()
        fact.source = text(source) ?: 'agent'
        fact.confidence = Math.max(0d, Math.min(1d, confidence))
        fact.updatedAt = Instant.now()
        return store.save(fact)
    }

    private static int score(AgentKnowledgeFact fact, String query) {
        int score = 0
        String key = fact.key?.toLowerCase(Locale.ROOT) ?: ''
        String value = fact.value?.toLowerCase(Locale.ROOT) ?: ''
        String tags = (fact.tags ?: []).join(' ').toLowerCase(Locale.ROOT)
        query.split('[^a-z0-9\\u4e00-\\u9fff._/-]+').findAll { it }.each { token ->
            if (key.contains(token)) score += 5
            if (tags.contains(token)) score += 3
            if (value.contains(token)) score += 1
        }
        return score
    }

    private static String required(Object value, String field) {
        String result = text(value)
        if (!result) {
            throw new IllegalArgumentException("${field} is required")
        }
        return result
    }

    private static String text(Object value) {
        return value == null ? '' : value.toString().trim()
    }
}
