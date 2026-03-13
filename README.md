# EvoForge

EvoForge is a self-evolving AI agent framework that can hot-update Groovy skills at runtime and grow a living skill library. This repo now contains a runnable Spring Boot + Groovy backend and a Flutter web console.

## What’s inside
- **Backend**: Spring Boot + Groovy, dynamic skill compiler, hot-reload registry, model hub abstraction, skill workbench.
- **Frontend**: Flutter web console for listing, editing, activating, and executing skills.

## Quick start

### Backend (Spring Boot + Groovy)
```bash
cd backend
# If you have Gradle installed
gradle bootRun
```

The backend will start on `http://localhost:8080` by default.

### Frontend (Flutter Web)
```bash
cd frontend
flutter pub get
flutter run -d chrome --dart-define=API_BASE_URL=http://localhost:8080
```

## Core APIs
- `GET /api/skills` list skills
- `POST /api/skills` create skill
- `POST /api/skills/{id}/activate` enable and hot-load skill
- `POST /api/skills/{id}/execute` run skill (supports `evaluate=true` to attach an LLM-backed evaluation)
- `GET /api/skills/{id}/history` list skill snapshots
- `POST /api/skills/{id}/rollback` rollback to a snapshot
- `POST /api/skills/propose` generate skill code via code model
- `POST /api/skills/evolve` generate + save a disabled skill from prompt
- `GET /api/audit` list all audit events
- `GET /api/audit/{skillId}` list audit events for a skill
- `POST /api/agent/respond` chat or call skill by id
- `POST /api/agent/route` route input to a skill (optionally execute)

## Notes
- Skills are stored in `backend/data/skills.json` (auto-created on first run).
- Hot-reload runs every 5 seconds by default (configurable in `application.yml`).
- Model providers are pluggable via `LlmProvider` and `CodeModelProvider` beans.
- Audit and history logs are stored in `backend/data/skill-audit.json` and `backend/data/skill-history.json`.
- Optional guardrails can be configured via `evoforge.skills.maxCodeSize` and `evoforge.skills.bannedPatterns`.
- Optional Groovy sandbox controls are available via `evoforge.skills.sandboxEnabled` and allowed import lists.
- Skill routing can optionally use the default LLM (`evoforge.skills.routerUseLlm=true`).

## Example skill
```groovy
package com.evoforge.dynamic

import com.evoforge.skills.Skill
import com.evoforge.model.SkillContext
import com.evoforge.model.SkillResult

class HelloSkill implements Skill {
    @Override
    SkillResult execute(SkillContext context) {
        return SkillResult.ok("Hello, ${context.input}")
    }
}
```

## Deployment
For production build, configuration, and serving guidance, see [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).

## Architecture
For a component overview and runtime flow, see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Contributing
We welcome engineers, researchers, and builders to help evolve EvoForge.
See [CONTRIBUTING.md](CONTRIBUTING.md) for how to get started and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for community guidelines.
