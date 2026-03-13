package com.evoforge.workbench

import com.evoforge.api.SkillProposalRequest
import com.evoforge.api.SkillProposalResponse
import com.evoforge.llm.ModelHub
import org.springframework.stereotype.Service

@Service
class SkillWorkbenchService {
    private final ModelHub modelHub

    SkillWorkbenchService(ModelHub modelHub) {
        this.modelHub = modelHub
    }

    SkillProposalResponse propose(SkillProposalRequest request) {
        String prompt = request.prompt ?: 'Generate a helpful skill'
        String generated = modelHub.getCodeModel(request.codeModel).generateSkill(prompt, [:])
        if (!generated) {
            generated = SkillTemplateFactory.defaultGroovySkill(request.name, prompt)
        }
        String entryClass = SkillTemplateFactory.detectEntryClass(generated)
        if (!entryClass) {
            entryClass = "com.evoforge.dynamic.${SkillTemplateFactory.classNameFrom(request.name)}"
        }
        String displayName = request.name ?: entryClass.tokenize('.').last()
        return new SkillProposalResponse(
            name: displayName,
            language: 'groovy',
            entryClass: entryClass,
            code: generated
        )
    }
}
