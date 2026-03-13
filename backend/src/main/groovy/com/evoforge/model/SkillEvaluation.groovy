package com.evoforge.model

import groovy.transform.ToString

@ToString(includeNames = true)
class SkillEvaluation {
    double score
    String verdict
    String rationale
    Map<String, Object> metrics = [:]
}
