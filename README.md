# EvoForge

EvoForge is a self-evolving AI agent framework that can hot-update Groovy skills at runtime and grow a living skill library. This repo now contains a runnable Spring Boot + Groovy backend and a Flutter web console.

## What’s inside
- **Backend**: Spring Boot + Groovy, dynamic skill compiler, hot-reload registry, model hub abstraction, skill workbench.
- **Frontend**: Flutter web console for listing, editing, activating, and executing skills.

## Quick start

### Backend (Spring Boot + Groovy)
The backend is Maven-first now, so no Gradle installation is required for normal development.

```bash
cd backend
# `createdb` is a PostgreSQL client command. It connects to the local
# PostgreSQL server and creates the default database EvoForge will use.
# Run it once, or skip it if the `evoforge` database already exists.
# createdb evoforge
# Run the backend with Maven
mvn spring-boot:run
```

The backend will start on `http://localhost:18080` by default. Override `EVOFORGE_SERVER_PORT` if this port is already in use.
It expects PostgreSQL on `jdbc:postgresql://localhost:5432/evoforge`; override `EVOFORGE_DB_URL`, `EVOFORGE_DB_USER`, and `EVOFORGE_DB_PASSWORD` when needed.

Sensitive local settings should live outside the repository. EvoForge will automatically try to load `~/.evoforge/application-secrets.yml` on startup. The file is optional, so the project still runs with the safe defaults in `backend/src/main/resources/application.yml`.

```bash
mkdir -p ~/.evoforge
cp docs/application-secrets.example.yml ~/.evoforge/application-secrets.yml
# Edit ~/.evoforge/application-secrets.yml with local database, RabbitMQ,
# signing, and Codex settings. Do not commit the real secrets file.
```

To run the PC-side remote agent over RabbitMQ, set `EVOFORGE_DEVICE_AGENT_ENABLED=true` and configure `EVOFORGE_RABBITMQ_*`. The PC agent consumes mobile commands from `evoforge.commands` and publishes progress, errors, approval requests, and results to `evoforge.events`.

### Frontend (Flutter Web)
Mobile-facing RabbitMQ Web STOMP settings are read from `frontend/config/evoforge.local.json`. This file is ignored by Git because client credentials are environment-specific. Use `frontend/config/evoforge.example.json` as the safe template.

```bash
cd frontend
flutter pub get
flutter run -d chrome
```

The console does not need a browser-to-backend HTTP route for normal use. It loads `frontend/config/evoforge.local.json`, connects to RabbitMQ Web STOMP or a JSON relay, sends UI reads/writes as signed `client_request` messages, and receives `client_response` events from the event exchange.

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
- `POST /api/agent/route` route input through skill candidate retrieval (optionally execute)
- `GET /api/agent/conversations` list persisted agent conversation threads
- `POST /api/agent/conversations` create a conversation thread
- `GET /api/agent/conversations/{threadId}/turns` load same-thread user/assistant turns
- `GET /api/codex/bridge/manifest` expose the small Codex-facing capability card
- `POST /api/codex/bridge/query` return focused project context plus matching skill summaries
- `GET /api/codex/bridge/skills` search the active EvoForge skill catalog for Codex
- `POST /api/codex/bridge/questions/ask` let Codex ask a blocking user question and wait for a mobile `human_response`
- `POST /api/codex/bridge/test-plan` return risk profile, quality routes, and persisted tester capabilities for a project/task
- `POST /api/codex/bridge/test-run` run a selected route or selected persisted tester capability ids
- `GET /api/device/tasks/{taskId}/events/page?limit=80&before=...` load long task timelines by cursor page for Codex/tester event logs
- `GET /api/tester/capabilities?projectKey=...` list persisted tester capability records
- `POST /api/tester/capabilities/discover` discover tester capabilities from project traits and save them
- `POST /api/tester/capabilities`, `PUT /api/tester/capabilities/{projectKey}/{id}`, and `DELETE /api/tester/capabilities/{projectKey}/{id}` create, edit, or remove tester capability records

The web console normally runs without a direct HTTP path to the backend. Configure `frontend/config/evoforge.local.json` with RabbitMQ Web STOMP or a JSON relay route; when that connection is ready, console data fetches use `client_request` messages on the `.request` routing key and receive `client_response` events from `evoforge.events`. Long-running tasks stay on the `.command` routing key, so quick UI queries are not blocked behind agent execution. The REST endpoints remain useful for local development and diagnostics.

## Remote device agent
When enabled, EvoForge acts as a PC-side agent node:
- Mobile sends commands to RabbitMQ routing key `user.<userId>.device.<deviceId>.command`.
- The PC agent consumes `evoforge.device.<deviceId>.commands`.
- The PC agent publishes events to `user.<userId>.device.<deviceId>.event`.
- The service-side event inbox can consume `evoforge.device.<deviceId>.events` and persist returned events.
- Events are also stored in PostgreSQL table `device_task_events`.

Example command:
```json
{
  "taskId": "uuid",
  "userId": "local-user",
  "deviceId": "local-pc",
  "type": "natural_language_task",
  "text": "Summarize the active EvoForge backend design",
  "requiresApproval": false,
  "attributes": {
    "threadId": "thread-uuid"
  }
}
```

Supported command types are `natural_language_task`, `codex_task`, `tester_task`, `client_request`, `approval_decision`, and `human_response`. Unknown command types are rejected with a `failed` event.
Set `EVOFORGE_COMMAND_SIGNING_SECRET` to require RabbitMQ commands to carry a valid `attributes.signature` HMAC. REST dispatchers sign commands automatically when this secret is configured. Signed commands must include a fresh `createdAt`; tune the replay window with `EVOFORGE_COMMAND_SIGNATURE_TTL_SECONDS`.
Signed commands also carry a `commandId`; repeated delivery of the same signed command id inside the replay window is rejected. In the default PostgreSQL mode this replay guard is persisted in `device_command_replay`, so it survives service restarts.
The signature payload is canonical JSON over `commandId`, `taskId`, `userId`, `deviceId`, `type`, `text`, `createdAt`, `skillId`, `llm`, `codeModel`, `requiresApproval`, and `attributes` with `attributes.signature` excluded. The Flutter shared helper `DeviceCommandSigner` implements the same canonicalization for mobile clients.
Events can also be signed with `EVOFORGE_EVENT_SIGNING_SECRET`; by default it falls back to `EVOFORGE_COMMAND_SIGNING_SECRET`. Mobile clients can verify inbound events with `DeviceEventVerifier` before adding them to `DeviceEventInbox`.

Mobile clients should build RabbitMQ messages through the shared factory rather than hand-writing JSON:
```dart
final factory = DeviceCommandFactory(
  userId: 'local-user',
  deviceId: 'local-pc',
  signer: DeviceCommandSigner(secret: commandSigningSecret),
);

final envelope = factory.naturalLanguageTask(
  text: '帮我检查本机项目状态',
  requiresApproval: false,
);

// Publish envelope.payload as JSON to envelope.exchange with envelope.routingKey.
```
Use `factory.testerTask(...)` when the client wants EvoForge to act as a standalone tester. Use `factory.codexTask(...)` with `attributes.evoforgeTester.enabled=true` when Codex should make the change first and then let EvoForge run the configured tester lane.
Use `factory.humanResponse(...)` when Codex has called `/api/codex/bridge/questions/ask` and the mobile client needs to unblock it with a human answer. The original question arrives as a task event with `status=needs_input` and `payload.codexQuestion`; the reply command should carry `attributes.codexQuestionAnswer = { questionId, taskId, answer, actor }`.
For inbound events, feed every RabbitMQ event JSON into `DeviceEventInbox`; it deduplicates by `eventId`, sorts each task timeline, derives recent task summaries, and captures heartbeat status snapshots for device capability updates.
For a complete mobile state holder, wrap both helpers with `DeviceMobileSession`; pass `eventSigningSecret` when event signing is enabled so unsigned or tampered events are rejected before they update local task state.
Mobile UI code should normally depend on `DeviceMobileController`; concrete RabbitMQ, WebSocket bridge, or other relay clients only need to implement `DeviceMessageTransport`.

For the actual mobile-to-RabbitMQ link, use a bridge-friendly transport:
```dart
final transport = DeviceRabbitMqWebStompTransport.connect(
  uri: Uri.parse('wss://rabbitmq.example.com/ws'),
  login: 'mobile-user',
  passcode: mobileRabbitMqPassword,
  eventExchange: 'evoforge.events',
  eventRoutingKey: 'user.local-user.device.local-pc.event',
);

final controller = DeviceMobileController(
  session: session,
  transport: transport,
);

await controller.start();
await controller.sendNaturalLanguageTask(text: '帮我检查本机项目状态');
```

`DeviceRabbitMqWebStompTransport` publishes commands to `/exchange/<commandExchange>/<commandRoutingKey>` and subscribes to `/exchange/<eventExchange>/<eventRoutingKey>`, so Flutter Web and mobile builds do not need a direct AMQP client. It also has `connectToQueue` for controlled deployments with a dedicated existing event queue, but the exchange subscription is the safer default because it avoids competing with the service-side event inbox queue.

If you run your own relay service instead of exposing RabbitMQ Web STOMP, use `DeviceJsonRelayTransport`. It sends command messages shaped as:
```json
{
  "type": "device_command",
  "exchange": "evoforge.commands",
  "routingKey": "user.local-user.device.local-pc.command",
  "payload": {}
}
```
The relay should publish `payload` to RabbitMQ and push returned `DeviceTaskEvent` JSON back to the WebSocket. The transport accepts direct event JSON, `{ "type": "device_event", "payload": { ... } }`, or `{ "events": [ ... ] }`.

Example RabbitMQ approval decision:
```json
{
  "taskId": "uuid",
  "userId": "local-user",
  "deviceId": "local-pc",
  "type": "approval_decision",
  "createdAt": "2026-05-07T00:00:00Z",
  "commandId": "unique-command-id",
  "attributes": {
    "decision": "approve",
    "actor": "mobile",
    "note": "optional",
    "signature": "hmac-sha256-hex"
  }
}
```

## Notes
- Skills are stored in PostgreSQL by default. The current skill code is also cached on local disk under `evoforge.skills.codeStoragePath` with a local checksum record; registry reloads compare the database checksum with the local checksum first and only read the database `code` column when the local copy is missing or stale. Saved skills are also mirrored to `skills/<skill-id>/` as `manifest.json`, `skill.groovy`, and `SKILL.md`.
- Hot-reload runs every 5 seconds by default (configurable in `application.yml`).
- Model providers are pluggable via `LlmProvider` and `CodeModelProvider` beans, and GPT/Claude/OpenAI-compatible configs can be managed from the Settings page.
- Open-ended ordinary tasks run through a dynamic agent runtime: the LLM proposes multiple routes, executes one registered tool at a time, observes failures, replans, and persists reusable facts in the agent knowledge base.
- Agent shell actions are not constrained by an allowed command list; `shell.run` accepts argv commands when `evoforge.agent.shellEnabled=true` and only applies the configured timeout. If all tool routes fail and a reusable capability is missing, EvoForge can propose a new skill to the mobile/command-center client, wait for confirmation or edited instructions, create the skill, and use it to continue the current task.
- Frontend conversations carry a stable `threadId`; the backend stores same-thread turns as active memory and injects them into later planner/direct-chat prompts, while durable facts stay in the agent knowledge base.
- Cross-network frontend/backend interaction is message-bus-first: clients publish `client_request`, `natural_language_task`, `codex_task`, approval, and `human_response` commands to the command exchange, while backend progress, observations, questions, and final responses are published as events.
- EvoForge can act as a tester through persisted quality capabilities. `TesterCapabilityService` discovers conventional commands from project traits, saves them in `tester_capabilities` or the file-backed capability store, optionally lets the configured model refine labels/tags/routes, and exposes the records in Settings for review and editing. Runtime execution still resolves configured project aliases and runs only stored capability ids whose executable and working directory pass validation; `evoforge.tester.projectCommands` and `defaultCommands` are compatibility fallback arrays only.
- Audit and history logs are stored in PostgreSQL.
- File-backed JSON storage remains available with `EVOFORGE_SKILL_STORAGE_BACKEND=file` or `evoforge.skills.storageBackend=file`.
- RabbitMQ remote control is disabled by default; enable it with `EVOFORGE_DEVICE_AGENT_ENABLED=true`.
- Optional guardrails can be configured via `evoforge.skills.maxCodeSize` and `evoforge.skills.bannedPatterns`.
- Optional Groovy sandbox controls are available via `evoforge.skills.sandboxEnabled` and allowed import lists.
- Skill routing first retrieves a small candidate set from skill metadata and returns `USE_SKILL`, `NO_SKILL`, or `NEEDS_NEW_SKILL`; it can optionally ask the default LLM to choose among those candidates with `evoforge.skills.routerUseLlm=true`.
- Tune routing breadth with `evoforge.skills.routerCandidateLimit` and `evoforge.skills.routerMinCandidateScore`.
- Local secrets are loaded from `~/.evoforge/application-secrets.yml`; see `docs/application-secrets.example.yml` for a copyable template.
- For multiple Codex-maintained projects, configure `evoforge.codexTask.defaultWorkspace` and `evoforge.codexTask.workspaces` as a project-key whitelist, then send `attributes.projectKey` with `codex_task`.
- When a `codex_task` opts into `attributes.evoforgeLearning`, EvoForge records the submitted task, selected project, Codex output/error, same-thread id, and previous latest change into project-scoped knowledge. Later Codex prompts do not receive the whole knowledge base; they receive a small Codex Bridge capability card and may call `/api/codex/bridge/query` for focused project context or `/api/codex/bridge/skills` for skill summaries. `ProjectKnowledgeContextService` builds each context pack using project scope, current task intent, tags, confidence, recency/latest-change signals, and the request `contextPolicy` budget.
- When a `codex_task` opts into `attributes.evoforgeTester.enabled=true`, EvoForge builds a risk-aware quality route from persisted tester capabilities and runs it after Codex succeeds. Test progress appears as `payload.testerProgress`; the final report appears as `payload.testerRun` with risk profile, route, evidence packets, and repair prompt, is recorded in project knowledge as the latest test result, and updates tester capability run stats/confidence when auto-optimization is enabled.
- When Codex is blocked on product intent, missing credentials, or another decision only the user can answer, it can call `/api/codex/bridge/questions/ask`. EvoForge publishes a `needs_input` event with `payload.codexQuestion`, the command center/mobile client replies with `human_response`, and the waiting Codex Bridge request returns the answer or times out after `evoforge.codexTask.questionTimeoutSeconds`.

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
