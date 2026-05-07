package com.evoforge.agent

class AgentRunResult {
    boolean success
    String output
    String threadId
    List<Map<String, Object>> conversationContext = []
    List<Map<String, Object>> routes = []
    List<Map<String, Object>> observations = []
    List<Map<String, Object>> knowledgeWrites = []
    String stopReason = ''
}
