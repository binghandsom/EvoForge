package com.evoforge.api

class SkillRouteDecision {
    String skillId
    String skillName
    double confidence
    String reason
    List<String> matched = []
}
