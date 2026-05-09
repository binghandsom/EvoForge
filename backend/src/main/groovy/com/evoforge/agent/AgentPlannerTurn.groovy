package com.evoforge.agent

class AgentPlannerTurn {
    String thought
    List<Map<String, Object>> routes = []
    String selectedRouteId
    Map<String, Object> action
    Map<String, Object> skillProposal
    String finalAnswer
    List<Map<String, Object>> knowledgeWrites = []
}
