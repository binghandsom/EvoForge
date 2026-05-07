# Deployment Guide

This guide covers production builds and deployment for the backend and frontend. For local development, see the Quick start section in README.

## Requirements
- Java 17
- Maven 3.9 or newer
- Flutter SDK 3.2 or newer
- PostgreSQL 18 or another compatible PostgreSQL server

Gradle is not required for the backend build; Maven is the supported build entrypoint.

## Backend build and run
1. `cd backend`
2. Create the database once: `createdb evoforge`
3. `mvn test`
4. `mvn package`
5. `java -jar target/evoforge-backend-0.1.0.jar`

By default the server listens on port 18080 and uses PostgreSQL:
- `EVOFORGE_SERVER_PORT` defaults to `18080`
- `EVOFORGE_DB_URL` defaults to `jdbc:postgresql://localhost:5432/evoforge`
- `EVOFORGE_DB_USER` defaults to `postgres`
- `EVOFORGE_DB_PASSWORD` defaults to an empty password

## External secrets file
Do not put local passwords, RabbitMQ credentials, signing secrets, or machine-specific Codex paths into the committed `application.yml`. The committed file imports an optional external secrets file:

```yaml
spring:
  config:
    import: optional:file:${user.home}/.evoforge/application-secrets.yml
```

Create the real file on each machine:

```bash
mkdir -p ~/.evoforge
cp docs/application-secrets.example.yml ~/.evoforge/application-secrets.yml
chmod 600 ~/.evoforge/application-secrets.yml
```

Then edit `~/.evoforge/application-secrets.yml` and place machine-local values there. Because the import is `optional:`, EvoForge still starts when the file is missing. Values in the external file override defaults from `backend/src/main/resources/application.yml`; environment variables can still be used for deployment systems that prefer env-based secrets.

Skill code, versions, audit events, and Git mirror metadata are stored in PostgreSQL. Flyway creates and upgrades the schema on startup. Each saved skill is also mirrored to the Git-backed skill library configured by `evoforge.skills.gitLibraryPath`.

## RabbitMQ remote device agent
The PC-side agent is disabled by default. Enable it when this EvoForge instance should receive mobile commands through a public RabbitMQ broker:

```yaml
spring:
  rabbitmq:
    host: <broker-host>
    port: 5671
    username: <pc-agent-user>
    password: <pc-agent-password>
    ssl:
      enabled: true

evoforge:
  deviceAgent:
    enabled: true
    userId: <user-id>
    deviceId: <device-id>
    commandSigningSecret: <shared-hmac-secret>
    eventSigningSecret: <shared-event-hmac-secret>
    commandSignatureTtlSeconds: 300
```

For Codex task execution across multiple projects, configure `defaultWorkspace` as the default project key and add explicit project aliases:

```yaml
evoforge:
  codexTask:
    enabled: true
    requiresApproval: true
    defaultWorkspace: evoforge
    workspaces:
      evoforge: /Users/you/Documents/project/binghandsom/EvoForge
      mobile-app: /Users/you/Documents/project/mobile-app
```

Mobile commands should select a project with `attributes.projectKey`, for example `evoforge` or `mobile-app`. If a command omits `projectKey`, the PC agent uses `defaultWorkspace`. The PC agent only resolves keys from this whitelist; it does not accept arbitrary paths from remote commands. `workingDirectory` remains as a legacy fallback for old single-project configs, but new configs should prefer `defaultWorkspace` plus `workspaces`.

Default routing:
- Command exchange: `evoforge.commands`
- Event exchange: `evoforge.events`
- Command routing key: `user.<userId>.device.<deviceId>.command`
- Event routing key: `user.<userId>.device.<deviceId>.event`

Approval decisions can also be sent as RabbitMQ commands with `type=approval_decision` and `attributes.decision=approve` or `reject`. Use separate RabbitMQ credentials for mobile and PC agents. The PC credential should consume only its device command queue and publish only its event routing key. Set `EVOFORGE_COMMAND_SIGNING_SECRET` on every trusted publisher/consumer so RabbitMQ commands must include a valid `attributes.signature` HMAC. Signed commands must include a fresh ISO-8601 `createdAt` and unique `commandId`; the default replay window is 300 seconds.
In PostgreSQL mode, seen signed `commandId` values are stored in `device_command_replay`, so replay protection survives service restarts until each record ages out of the configured TTL window.
Mobile publishers should compute the HMAC over the canonical command payload and exclude `attributes.signature` from the signed attributes. The Flutter shared helper `DeviceCommandSigner` contains the reference client-side implementation.
PC agents sign outbound events with `EVOFORGE_EVENT_SIGNING_SECRET`, which defaults to `EVOFORGE_COMMAND_SIGNING_SECRET` when unset. Mobile clients should verify signed events with `DeviceEventVerifier` before accepting them into local task state.
Use `DeviceCommandFactory` on the mobile side to generate `exchange`, `routingKey`, and signed JSON payloads for `natural_language_task`, `codex_task`, and `approval_decision`; the RabbitMQ client only needs to publish that envelope.
Use `DeviceEventInbox` for events consumed from the event queue; it removes duplicate deliveries, keeps per-task timelines ordered, builds task summaries, and updates the latest device status from heartbeat payloads.
Use `DeviceMobileSession` as the mobile UI state boundary when possible: pass `eventSigningSecret`, publish command envelopes from it, feed consumed event JSON back into it, and use its approve/reject helpers when a task is waiting for approval.
Use `DeviceMobileController` above a concrete `DeviceMessageTransport` implementation so the UI is isolated from the chosen RabbitMQ client or bridge library.

### Mobile bridge transports
Flutter Web and many mobile builds are easier to keep portable when they use WebSocket instead of direct AMQP. EvoForge includes two shared transport implementations:

- `DeviceRabbitMqWebStompTransport`: connect to RabbitMQ Web STOMP, publish command payloads to `/exchange/<commandExchange>/<commandRoutingKey>`, and subscribe to `/exchange/<eventExchange>/<eventRoutingKey>`.
- `DeviceJsonRelayTransport`: connect to your own WebSocket relay. The relay receives `{ type, exchange, routingKey, payload }`, publishes `payload` to RabbitMQ, and streams returned `DeviceTaskEvent` JSON back to the client.

For RabbitMQ Web STOMP:
```bash
rabbitmq-plugins enable rabbitmq_web_stomp
```

Use `wss://<broker-host>/ws` in production, usually behind TLS or a reverse proxy. RabbitMQ's default local Web STOMP endpoint is `ws://127.0.0.1:15674/ws`.

Mobile-side setup:
```dart
final transport = DeviceRabbitMqWebStompTransport.connect(
  uri: Uri.parse('wss://rabbitmq.example.com/ws'),
  login: 'mobile-user',
  passcode: mobileRabbitMqPassword,
  eventExchange: 'evoforge.events',
  eventRoutingKey: 'user.<userId>.device.<deviceId>.event',
);

final controller = DeviceMobileController(
  session: session,
  transport: transport,
);
```

Prefer exchange subscriptions for normal mobile sessions. `DeviceRabbitMqWebStompTransport.connectToQueue` is available for a dedicated pre-created queue, but mobile clients should not consume the same `evoforge.device.<deviceId>.events` queue as the service-side event inbox unless that fan-out behavior is intentional.

File-backed JSON storage remains available for local fallback with `EVOFORGE_SKILL_STORAGE_BACKEND=file` or `evoforge.skills.storageBackend=file`; in that mode the server writes data files relative to the process working directory:
- `data/skills.json`
- `data/skill-audit.json`
- `data/skill-history.json`

You can customize settings in `backend/src/main/resources/application.yml`. Standard Spring Boot configuration overrides also apply, including environment variables and JVM system properties.

## Frontend build and serve
1. `cd frontend`
2. `flutter pub get`
3. `flutter build web --dart-define=API_BASE_URL=http://<backend-host>:18080`

The build output is located at `frontend/build/web`. Serve this directory with any static file server.

The frontend loads mobile connection settings from `config/evoforge.local.json` by default. Put the mobile RabbitMQ Web STOMP URL, low-privilege mobile login, passcode, vhost, and signing secrets there before building. The file is intentionally ignored by Git; keep `config/evoforge.example.json` as the committed template. To use another asset path:

```bash
flutter build web \
  --dart-define=API_BASE_URL=http://<backend-host>:18080 \
  --dart-define=EVOFORGE_FRONTEND_CONFIG_ASSET=config/evoforge.local.json
```

## Health checks
The backend exposes Spring Boot Actuator endpoints:
- `GET /actuator/health`
- `GET /actuator/info`
