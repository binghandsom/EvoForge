package com.evoforge.policy

import com.evoforge.core.EvoForgeProperties
import com.evoforge.model.SkillDefinition
import org.springframework.stereotype.Component

@Component
class DefaultSkillPolicy implements SkillPolicy {
    private final EvoForgeProperties properties

    DefaultSkillPolicy(EvoForgeProperties properties) {
        this.properties = properties
    }

    @Override
    List<String> validate(SkillDefinition skill) {
        List<String> violations = []
        String code = skill.code ?: ''

        int maxSize = properties.skills.maxCodeSize
        if (maxSize > 0 && code.length() > maxSize) {
            violations << "Code length ${code.length()} exceeds max ${maxSize}"
        }

        def patterns = properties.skills.bannedPatterns ?: []
        patterns.each { pattern ->
            if (!pattern) return
            if (code.toLowerCase().contains(pattern.toString().toLowerCase())) {
                violations << "Code contains banned pattern: ${pattern}"
            }
        }

        return violations
    }
}
