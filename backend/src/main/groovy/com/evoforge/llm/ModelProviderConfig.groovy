package com.evoforge.llm

import groovy.transform.ToString

import java.time.Instant

@ToString(includeNames = true, excludes = ['apiKey'])
class ModelProviderConfig {
    String id
    String name
    String providerType = 'openai'
    String baseUrl = ''
    String modelName = ''
    String apiKey = ''
    boolean enabled = true
    boolean supportsLlm = true
    boolean supportsCodeModel = false
    boolean defaultLlm = false
    boolean defaultCodeModel = false
    Map<String, Object> metadata = [:]
    Instant createdAt = Instant.now()
    Instant updatedAt = Instant.now()
}
