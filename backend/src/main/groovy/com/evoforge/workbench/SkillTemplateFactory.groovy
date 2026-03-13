package com.evoforge.workbench

class SkillTemplateFactory {
    static String classNameFrom(String name) {
        String base = (name ?: 'GeneratedSkill').replaceAll(/[^A-Za-z0-9]/, ' ')
        base = base.split(' ').collect { it.capitalize() }.join('')
        return base ? base : 'GeneratedSkill'
    }

    static String defaultGroovySkill(String name, String prompt) {
        String className = classNameFrom(name)
        return """
package com.evoforge.dynamic

import com.evoforge.skills.Skill
import com.evoforge.model.SkillContext
import com.evoforge.model.SkillResult

class ${className} implements Skill {
    @Override
    SkillResult execute(SkillContext context) {
        def reply = context.llm.chat("${prompt ?: 'Handle user input'}: ${'$'}{context.input}")
        return SkillResult.ok(reply)
    }
}
""".stripIndent()
    }

    static String detectEntryClass(String code) {
        if (!code) return null
        def pkgMatch = (code =~ /(?m)^\s*package\s+([A-Za-z0-9_.]+)\s*$/)
        String pkg = pkgMatch ? pkgMatch[0][1] : null
        def clsMatch = (code =~ /(?m)^\s*(public\s+)?class\s+([A-Za-z0-9_]+)/)
        if (!clsMatch) return null
        String cls = clsMatch[0][2]
        return pkg ? "${pkg}.${cls}" : cls
    }
}
