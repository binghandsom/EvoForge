package com.evoforge.agent

class AgentRunResult {
    boolean success
    String output
    List<Map<String, Object>> routes = []
    List<Map<String, Object>> observations = []
    List<Map<String, Object>> knowledgeWrites = []
    String stopReason = ''
}
