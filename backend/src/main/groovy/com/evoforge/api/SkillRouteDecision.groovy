package com.evoforge.api

class SkillRouteDecision {
    String action = 'NO_SKILL'
    String skillId
    String skillName
    double confidence
    String reason
    List<String> matched = []
    List<SkillRouteCandidate> candidates = []
    String suggestedSkillName
    String suggestedSkillPurpose
}
