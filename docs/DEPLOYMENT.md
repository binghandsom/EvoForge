# Deployment Guide

This guide covers production builds and deployment for the backend and frontend. For local development, see the Quick start section in README.

## Requirements
- Java 17
- Gradle
- Flutter SDK 3.2 or newer

## Backend build and run
1. `cd backend`
2. `gradle bootJar`
3. `java -jar build/libs/evoforge-backend-0.1.0.jar`

By default the server listens on port 8080 and writes data files relative to the process working directory:
- `data/skills.json`
- `data/skill-audit.json`
- `data/skill-history.json`

You can customize settings in `backend/src/main/resources/application.yml`. Standard Spring Boot configuration overrides also apply, including environment variables and JVM system properties.

## Frontend build and serve
1. `cd frontend`
2. `flutter pub get`
3. `flutter build web --dart-define=API_BASE_URL=http://<backend-host>:8080`

The build output is located at `frontend/build/web`. Serve this directory with any static file server.

## Health checks
The backend exposes Spring Boot Actuator endpoints:
- `GET /actuator/health`
- `GET /actuator/info`
