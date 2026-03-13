package com.evoforge.skills

import com.evoforge.model.SkillContext
import com.evoforge.model.SkillResult

interface Skill {
    SkillResult execute(SkillContext context)
}
