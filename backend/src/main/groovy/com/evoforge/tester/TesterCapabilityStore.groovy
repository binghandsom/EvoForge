package com.evoforge.tester

interface TesterCapabilityStore {
    List<TesterCapability> loadAll()
    List<TesterCapability> findByProject(String projectKey)
    Optional<TesterCapability> findByProjectAndId(String projectKey, String id)
    TesterCapability save(TesterCapability capability)
    void delete(String projectKey, String id)
}

