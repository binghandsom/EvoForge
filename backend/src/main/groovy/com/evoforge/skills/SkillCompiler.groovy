package com.evoforge.skills

import com.evoforge.core.EvoForgeProperties
import com.evoforge.model.SkillDefinition
import groovy.lang.GroovyClassLoader
import groovy.control.CompilerConfiguration
import groovy.control.customizers.SecureASTCustomizer
import org.springframework.stereotype.Component

@Component
class SkillCompiler {
    private final EvoForgeProperties properties

    SkillCompiler(EvoForgeProperties properties) {
        this.properties = properties
    }

    Class<? extends Skill> compile(SkillDefinition definition) {
        if (!definition.code) {
            throw new IllegalArgumentException('Skill code is empty')
        }
        if (definition.language && definition.language.toLowerCase() != 'groovy') {
            throw new IllegalArgumentException("Unsupported language: ${definition.language}")
        }

        CompilerConfiguration config = new CompilerConfiguration()
        if (properties.skills.sandboxEnabled) {
            SecureASTCustomizer secure = new SecureASTCustomizer()
            def allowedImports = properties.skills.allowedImports ?: []
            def allowedStarImports = properties.skills.allowedStarImports ?: []
            def disallowedImports = properties.skills.disallowedImports ?: []
            if (allowedImports) {
                secure.importsWhitelist = allowedImports
            }
            if (allowedStarImports) {
                secure.starImportsWhitelist = allowedStarImports
            }
            if (disallowedImports) {
                secure.importsBlacklist = disallowedImports
            }
            config.addCompilationCustomizers(secure)
        }

        GroovyClassLoader loader = new GroovyClassLoader(this.class.classLoader, config)
        Class<?> compiled = loader.parseClass(definition.code)

        Class<?> entryClass
        if (definition.entryClass) {
            entryClass = loader.loadClass(definition.entryClass)
        } else {
            entryClass = compiled
        }

        if (!Skill.isAssignableFrom(entryClass)) {
            throw new IllegalArgumentException("Skill class must implement com.evoforge.skills.Skill, got ${entryClass.name}")
        }
        return (Class<? extends Skill>) entryClass
    }
}
