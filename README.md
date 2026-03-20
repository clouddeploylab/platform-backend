# Monolotic App Backend

Spring Boot API for authentication, repository discovery, deployment orchestration, and Jenkins log streaming.

## Tech stack

- Java 21
- Spring Boot 3
- PostgreSQL
- Jenkins + ArgoCD integration
- WebSocket/SSE log streaming

## Clone and run

1. Clone and enter the project:

```bash
git clone <your-backend-repo-url>
cd monolotic-app-backend
```

2. Start PostgreSQL locally (example with Docker):

```bash
docker run --name monolotic-app-db \
  -e POSTGRES_DB=monolotic-app-db \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=1234 \
  -p 5432:5432 -d postgres:16
```

3. Configure local settings:

- Default local config is in `src/main/resources/application-dev.yml`.
- Replace secrets/tokens with your own values before sharing or deploying.
- Frontend origin must match `cors.allowed-origins` (default `http://localhost:3000`).

4. Run the backend:

```bash
./gradlew bootRun
```

The API starts on `http://localhost:8080`.

## Run tests and checks

```bash
./gradlew test
./gradlew compileJava
```

## Useful endpoints

- `POST /api/v1/auth/github`
- `GET /api/v1/repos`
- `POST /api/v1/projects`
- `GET /api/v1/projects`
- `GET /api/v1/jenkins/logs/stream?job=<job>&build=<build>`
- `WS /ws/jenkins/logs?job=<job>&build=<build>&token=<jwt>`
- `WS /ws/jenkins/logs?job=<job>&queueItem=<id>&token=<jwt>`

## Notes for new contributors

- `POST /api/v1/projects` now returns deployment queue metadata (`queueItemId`, `queueUrl`, `jenkinsJobName`) so frontend can start log streaming immediately after deploy click.
- WebSocket handshake is JWT-protected through the handshake interceptor (`token` query parameter).
