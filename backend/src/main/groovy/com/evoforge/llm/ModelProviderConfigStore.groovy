package com.evoforge.llm

interface ModelProviderConfigStore {
    List<ModelProviderConfig> loadAll()
    Optional<ModelProviderConfig> findById(String id)
    ModelProviderConfig save(ModelProviderConfig config)
    void delete(String id)
}
