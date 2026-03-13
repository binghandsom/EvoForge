package com.evoforge.model

import groovy.transform.ToString

@ToString(includeNames = true)
class SkillResult {
    boolean success
    Object output
    String error
    SkillEvaluation evaluation
    Map<String, Object> meta = [:]

    static SkillResult ok(Object output) {
        new SkillResult(success: true, output: output)
    }

    static SkillResult fail(String error) {
        new SkillResult(success: false, error: error)
    }
}
