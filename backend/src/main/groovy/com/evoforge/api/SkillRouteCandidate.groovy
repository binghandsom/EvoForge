package com.evoforge.api

class SkillRouteCandidate {
    String skillId
    String skillName
    double score
    String description
    List<String> keywords = []
    List<String> tags = []
    List<String> triggerExamples = []
    List<String> antiTriggers = []
    List<String> matched = []
}
