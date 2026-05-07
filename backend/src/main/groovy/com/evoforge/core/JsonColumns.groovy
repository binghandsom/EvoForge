package com.evoforge.core

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper

final class JsonColumns {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {}

    private JsonColumns() {
    }

    static String writeMap(ObjectMapper objectMapper, Map<String, Object> value) {
        return objectMapper.writeValueAsString(value ?: [:])
    }

    static Map<String, Object> readMap(ObjectMapper objectMapper, String value) {
        if (!value) {
            return [:]
        }
        return objectMapper.readValue(value, MAP_TYPE)
    }
}
