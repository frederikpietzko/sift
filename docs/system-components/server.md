# Server

`server` is the Spring Boot 4 application (`org.sift.server`) that fronts the platform: it persists
repositories, agent runs and review results in Postgres, applies `CodeReview` custom resources to the
cluster via the fabric8 client, and consumes lifecycle events from RabbitMQ. It is the only component
the UI talks to.

Status: Step 7 (complete for this iteration) — Postgres, Exposed and Flyway are wired (Step 2), the
repositories API with encrypted tokens and k8s Secret mirroring is implemented (Step 3,
[ADR 0012](../adrs/0012-server-managed-repository-credentials.md)), the agent runs API
(create/get/list/cancel), the `CodeReview` CR adapter and the `code-review.status` consumer are in place
(Step 4), the SSE run watch backed by Postgres `LISTEN/NOTIFY` is implemented (Step 5,
[ADR 0013](../adrs/0013-agent-run-watch-via-pg-notify.md)), the `code-review.completed` consumer with the
review results query API is in place (Step 6) and the Kubernetes manifests plus architecture documentation
are finished (Step 7). The API is an **OAuth2 resource server**: every `/api/**` call needs a JWT bearer token
from the configured OIDC provider, callers are provisioned into `users` and runs are attributed to them
([ADR 0016](../adrs/0016-oauth2-resource-server-and-user-attribution.md), superseding
[ADR 0014](../adrs/0014-defer-server-api-authentication.md)); see [Security](#security). The code is split into
Spring Modulith application modules with verified boundaries ([ADR 0017](../adrs/0017-server-package-structure-with-spring-modulith.md),
see [Module layout](#module-layout)).

## API summary

All endpoints are JSON (`application/json`) unless noted; errors are RFC 7807 `application/problem+json`
produced by `ApiExceptionHandler` (domain errors) or the security handlers (`401`/`403`). Every `/api/**`
endpoint except `GET /api/v1/auth/config` requires `Authorization: Bearer <JWT>` and answers `401` otherwise
(see [Security](#security)); the `Errors` column below lists the endpoint-specific codes only.

| Method | Path | Success | Errors | Section |
|---|---|---|---|---|
| `GET` | `/api/v1/auth/config` (anonymous) | `200` | — | [Security](#security) |
| `GET` | `/api/v1/me` | `200` | — | [Security](#security) |
| `POST` | `/api/v1/repositories` | `201` | `400`, `409` | [Repositories](#repositories-api) |
| `GET` | `/api/v1/repositories` | `200` | — | [Repositories](#repositories-api) |
| `GET` | `/api/v1/repositories/{id}` | `200` | `400`, `404` | [Repositories](#repositories-api) |
| `PUT` | `/api/v1/repositories/{id}` | `200` | `400`, `404` | [Repositories](#repositories-api) |
| `DELETE` | `/api/v1/repositories/{id}` | `204` | `400`, `404`, `409` | [Repositories](#repositories-api) |
| `POST` | `/api/v1/agents` | `202` | `400`, `404`, `500` | [Agent runs](#agent-runs-api) |
| `GET` | `/api/v1/agents` | `200` | `400` | [Agent runs](#agent-runs-api) |
| `GET` | `/api/v1/agents/{id}` | `200` | `404` | [Agent runs](#agent-runs-api) |
| `POST` | `/api/v1/agents/{id}/cancel` | `202` | `404`, `409` | [Agent runs](#agent-runs-api) |
| `GET` | `/api/v1/agents/watch` | `200` `text/event-stream` | `400`, `406` | [Watch](#agent-run-watch-sse) |
| `GET` | `/api/v1/results` | `200` | `400` | [Results](#review-results-api) |
| `GET` | `/api/v1/results/{id}` | `200` | `400`, `404` | [Results](#review-results-api) |
| `GET` | `/api/v1/results/{id}/findings` | `200` | `400`, `404` | [Results](#review-results-api) |
| `GET` | `/actuator/health`, `/actuator/health/{liveness,readiness}`, `/actuator/info` (anonymous) | `200`/`503` | — | Actuator |

Ingress: REST from clients; AMQP consumers on `sift.server.code-review.status` and
`sift.server.code-review.completed` (see [Queues](#queues-and-dead-lettering)). Egress: Postgres (Hikari
pool + one `LISTEN` connection), RabbitMQ, the Kubernetes API (`CodeReview` CRs and `sift-repo-*` Secrets in
`sift.server.namespace`), the OIDC provider (OpenID configuration + JWKS of `issuer-uri`, fetched lazily on the
first token and cached).

## Module layout

The server is organised as **Spring Modulith application modules** ([ADR 0017](../adrs/0017-server-package-structure-with-spring-modulith.md)):
every direct sub-package of `org.sift.server` is a module, its base package is the module's API (domain
model, service, SPI types) and the technical sub-packages `persistence`, `web`, `messaging`, `adapters`,
`secrets` and `watch` are internal to it. Each module declares the modules it may use in a `ModuleMetadata`
class (`@ApplicationModule(allowedDependencies = …)`); `ModularityTest` verifies the arrangement (no cycles,
no access to another module's internals, only declared dependencies) and renders it to
`server/build/spring-modulith-docs` (C4 PlantUML + AsciiDoc).

| Module | Depends on | Purpose |
|---|---|---|
| `api` | — | RFC 7807 error handling, shared `Page`/`PageResponse`. |
| `config` | — | `ServerProperties`, coroutine scope, `KubernetesClient`, queue topology. |
| `users` | `api`, `config` | Authenticated identities, `@CurrentUser`, provisioning filter; exposes `persistence` as a named interface (`users :: persistence`) so `agents` can join the `users` table. |
| `security` | `config`, `users` | OAuth2 resource server filter chain, problem-detail handlers, `/api/v1/auth/config`. |
| `repositories` | `api`, `config` | Repositories and their credentials; `RepositoryUsageCheck` SPI lets other modules veto deletion. |
| `agents` | `api`, `config`, `repositories`, `users`, `users :: persistence` | Agent runs: REST, `CodeReview` CR adapter, status consumer, SSE watch, `RepositoryUsageCheck` implementation. |
| `results` | `api`, `config`, `agents` | Review results ingested from `code-review.completed`; links to and completes runs via `AgentRunService`. |

| Path | Purpose |
|---|---|
| `server/module.yaml` | `jvm/app`, applies the shared detekt and Spring templates, `runtimeClasspathMode: jars`; `spring-modulith-api` (annotations) in `dependencies`, `spring-modulith-starter-test` in `test-dependencies`. |
| `src/org/sift/server/Main.kt`, `Application.kt` | Entry point. Imports `ExposedAutoConfiguration`, excludes `DataSourceTransactionManagerAutoConfiguration` so Exposed's `SpringTransactionManager` is the only transaction manager. |
| `src/org/sift/server/*/ModuleMetadata.kt` | One per module: `@ApplicationModule(allowedDependencies = …) @PackageInfo`; `users/persistence/ModuleMetadata.kt` carries `@NamedInterface("persistence")`. |
| `src/org/sift/server/api/` | `ApiExceptionHandler` (`@RestControllerAdvice`, RFC 7807 `ProblemDetail`), `NotFoundException` (404), `ConflictException` (409), `Paging.kt` (`Page<T>`, `PageResponse<T>`). |
| `src/org/sift/server/config/ServerProperties.kt` | `@ConfigurationProperties("sift.server")`, validated; nested `Watch { enabled, heartbeat }` and `Auth { clientId, scopes, claims { username, email } }`. |
| `src/org/sift/server/config/ApplicationCoroutineScope.kt` | The application `CoroutineScope` bean (`SupervisorJob + Dispatchers.IO + CoroutineName`), cancelled on context close. |
| `src/org/sift/server/config/KubernetesConfiguration.kt` | Default `KubernetesClient` bean (`KubernetesClientBuilder().build()`, backs off if one is already defined). |
| `src/org/sift/server/config/MessagingConfiguration.kt` | `ServerQueues` constants (queue names and the `CONSUMERS_ENABLED` property key) and the `Declarables` bean with the server's consumer queues, DLX and bindings. |
| `src/org/sift/server/security/SecurityConfiguration.kt` | The `SecurityFilterChain`: stateless, CSRF off, `oauth2ResourceServer.jwt`, permit list `ANONYMOUS_GET_PATHS` (`/actuator/health`, `/actuator/health/**`, `/actuator/info`, `/api/v1/auth/config`), everything else `authenticated`; registers `users.UserProvisioningFilter` after `BearerTokenAuthenticationFilter`. |
| `src/org/sift/server/security/ProblemDetailAuthHandlers.kt` | `ProblemDetailAuthenticationEntryPoint` (delegates to `BearerTokenAuthenticationEntryPoint` for `WWW-Authenticate`, then writes a `401` problem) and `ProblemDetailAccessDeniedHandler` (`403` problem). |
| `src/org/sift/server/security/AuthConfigController.kt` | Anonymous `GET /api/v1/auth/config` → `AuthConfigResponse { issuerUri, clientId, scopes }`. |
| `src/org/sift/server/users/User.kt`, `UserService.kt` | Domain model and `@Transactional provision(jwt)` mapping the configured claims (username falls back to `sub`), `get(id)`. |
| `src/org/sift/server/users/UserProvisioningFilter.kt` | `OncePerRequestFilter`: for a `JwtAuthenticationToken` calls `UserService.provision(jwt)` and stores the `User` as request attribute. |
| `src/org/sift/server/users/CurrentUser.kt` | `@CurrentUser` parameter annotation, `CurrentUserArgumentResolver` and the `WebMvcConfigurer` registering it. |
| `src/org/sift/server/users/persistence/UsersTable.kt`, `UserRepository.kt` | Exposed DSL table for `users` and the blocking `upsert` on `(issuer, subject)` / `findById` / `findByIdentity` (named interface `users :: persistence`). |
| `src/org/sift/server/users/web/MeController.kt`, `UserResponse.kt` | `GET /api/v1/me` → `UserResponse { id, subject, issuer, username, email }`. |
| `src/org/sift/server/repositories/Repository.kt` | Domain model (`Repository`, `EncryptedToken`). |
| `src/org/sift/server/repositories/RepositoryService.kt` | `@Transactional` use cases, URL validation, `SecretRef` lookup for the CR builder; `delete` asks every `RepositoryUsageCheck` before removing Secret and row (`409` when one vetoes). |
| `src/org/sift/server/repositories/RepositoryUsageCheck.kt` | SPI (`fun interface`) implemented by modules that reference repositories; returns what still uses the repository or `null`. |
| `src/org/sift/server/repositories/persistence/RepositoriesTable.kt`, `RepositoryRepository.kt` | Exposed DSL table for `repositories` and the blocking repository (insert/update/find/delete). |
| `src/org/sift/server/repositories/secrets/TokenCipher.kt` | AES-256-GCM encryption of tokens at rest, keyed by `SIFT_SERVER_ENCRYPTION_KEY`. |
| `src/org/sift/server/repositories/secrets/RepositorySecretSync.kt` | Server-side-applies/deletes the per-repository k8s Secret. |
| `src/org/sift/server/repositories/web/RepositoryController.kt`, `RepositoryDtos.kt` | `/api/v1/repositories` REST endpoints and request/response DTOs. |
| `src/org/sift/server/agents/AgentRun.kt` | Domain model (`AgentRun`, `RunCreator`, `AgentKind`, `AgentPhase`, `RunSource`, `AgentRunFilter` incl. `resolveCreatedBy(mine, createdBy, user)`). |
| `src/org/sift/server/agents/AgentRunService.kt` | Use cases: `create(request, user)` (persist with `createdBy` → apply CR → record uid), get, list, cancel, `applyStatus` upsert from events (`EXTERNAL` runs keep `createdBy = null`), `findByExecutionId` and `completeWithResult` for the `results` module. |
| `src/org/sift/server/agents/AgentRunRepositoryUsageCheck.kt` | `RepositoryUsageCheck` implementation: vetoes deleting a repository with non-terminal runs. |
| `src/org/sift/server/agents/adapters/AgentKindAdapter.kt`, `CodeReviewAdapter.kt` | Per-kind bridge to the cluster; `CodeReviewAdapter` builds, creates and foreground-deletes `CodeReview` CRs. |
| `src/org/sift/server/agents/persistence/AgentRunsTable.kt`, `AgentRunRepository.kt` | Exposed DSL table for `agent_runs` (`spec` as `jsonb` via Jackson 3; `repository_id`/`created_by` are plain columns, the FKs live in Flyway) and the blocking repository (insert/update/updateStatus/findById/findByCrUid/findByExecutionId/list/findUpdatedSince/hasActiveRuns; reads left-join `users` for the creator's username). |
| `src/org/sift/server/agents/messaging/AgentStatusConsumer.kt` | `@RabbitListener` on `sift.server.code-review.status` feeding `AgentRunService.applyStatus`. |
| `src/org/sift/server/agents/web/AgentRunController.kt`, `AgentRunDtos.kt` | `/api/v1/agents` REST endpoints and request/response DTOs (`CreateAgentRunRequest`, `CodeReviewRunSpec`, `AgentRunResponse`). |
| `src/org/sift/server/agents/watch/AgentRunEvent.kt`, `AgentRunEvents.kt` | SSE payload (`AgentRunEvent { type: SNAPSHOT/UPDATED, run }`) and the in-process `SharedFlow` fan-out (no replay, buffer 256, `DROP_OLDEST`). |
| `src/org/sift/server/agents/watch/PgNotificationConnection.kt`, `PgNotificationListener.kt` | Dedicated non-pooled `LISTEN sift_agent_runs` connection (`application_name=sift-server-watch`) and the single listener coroutine that reloads notified runs and emits `UPDATED`, reconnecting with backoff. |
| `src/org/sift/server/agents/watch/AgentRunWatchService.kt`, `AgentWatchController.kt` | Snapshot + live-event flow composition (`WatchRequest`) and `GET /api/v1/agents/watch` (`Flow<ServerSentEvent<AgentRunEvent>>`, heartbeats). |
| `src/org/sift/server/results/ReviewResult.kt` | Domain model (`ReviewResult`, `ReviewFinding`, `ReviewResultFilter`). |
| `src/org/sift/server/results/ReviewResultService.kt` | `@Transactional store(event)` ingestion (idempotent, links the run via `AgentRunService.findByExecutionId` and completes it via `completeWithResult`) plus read-only `get`/`list`/`findings`/`findingCounts`. |
| `src/org/sift/server/results/persistence/ReviewResultsTable.kt`, `ReviewFindingsTable.kt`, `ReviewResultRepository.kt` | Exposed DSL tables for `review_results` and `review_findings` and the blocking repository (`insert` via `insertIgnore`, `findById`, `findByExecutionId`, `list`, `findings`, `countFindings`). |
| `src/org/sift/server/results/messaging/ReviewResultConsumer.kt` | `@RabbitListener` on `sift.server.code-review.completed` feeding `ReviewResultService.store`. |
| `src/org/sift/server/results/web/ReviewResultController.kt`, `ReviewResultDtos.kt` | `/api/v1/results` REST endpoints and response DTOs. |
| `resources/application.yaml` | Default configuration, all secrets/hosts from environment variables. |
| `resources/db/migration/` | Flyway migrations (`V1__init.sql`, `V2__users.sql`). |
| `test/org/sift/server/` | Tests mirror the module packages (`agents/web/AgentRunControllerTest`, `agents/persistence/AgentRunRepositoryTest`, `agents/watch/*`, `results/messaging/ReviewResultConsumerIntegrationTest`, …). `ModularityTest` (Spring Modulith `verify()`, expected module set, documentation rendering; test classes are excluded from the analysis because fixtures are shared across modules). Fixtures: `security/TestSecurityConfiguration` (`@Primary JwtDecoder` decoding base64url JSON claim sets built by `TestTokens.bearer(...)`, plus the MockMvc post-processor `TestTokens.authenticated(...)` — no IdP is contacted, the real filter chain runs), `users/TestUsers`, `PostgresIntegrationTest` (shared `@SpringBootTest` base: singleton Testcontainers Postgres, mocked `KubernetesClient`, random key, consumers and watch listener disabled, test decoder imported), `RabbitMqIntegrationTest` (adds a singleton Testcontainers RabbitMQ and enables the consumers), `agents/watch/WatchIntegrationTest` (listener enabled on a `RANDOM_PORT` server). Coverage: `security/SecurityConfigurationTest` (401 problem + `WWW-Authenticate` without/with rejected token, anonymous probes and `/api/v1/auth/config`), `security/AuthConfigControllerTest`, `users/**` (upsert semantics against Postgres, claim mapping and `sub` fallback, `MeControllerTest`), `ServerEndToEndTest` (`RANDOM_PORT`, consumers + watch on: REST create with bearer → status event → SSE `UPDATED` → completed event → results API and run `SUCCESS`; plus a `401` without token), `ApplicationTest`, `ServerPropertiesTest`, `ApiExceptionHandlerTest`, `repositories/**` (cipher, Secret sync via fabric8 mock server, service with mockk incl. the `RepositoryUsageCheck` veto, `@WebMvcTest` controller, repository against Postgres), `agents/**` (service with mockk incl. `completeWithResult`, `CodeReviewAdapter` via fabric8 mock server, `@WebMvcTest` controller incl. `mine`/`createdBy`, repository against Postgres incl. `created_by` and `hasActiveRuns`, `AgentStatusConsumerIntegrationTest` against Postgres + RabbitMQ, `AgentRunEventsTest`, `AgentWatchControllerTest` driving the `Flow` with a mocked repository, `PgNotificationListenerIntegrationTest` incl. `pg_terminate_backend` reconnect, `AgentWatchSseIntegrationTest` reading the SSE wire format with `java.net.http.HttpClient`), `results/**` (repository against Postgres incl. duplicate `execution_id`, service with a mocked `AgentRunService`, `@WebMvcTest` controller, `ReviewResultConsumerIntegrationTest` against Postgres + RabbitMQ incl. redelivery). `@WebMvcTest` slices import `SecurityConfiguration` + `TestSecurityConfiguration` and mock `UserService`. |

Stack: Spring Boot 4.1.1 (Web MVC, Validation, Actuator, AMQP, Flyway, OAuth2 Resource Server / Spring Security 7;
`spring-security-test` in tests), Exposed 1.5.0
(`exposed-spring-boot4-starter`, `exposed-jdbc`, `exposed-json`, `exposed-java-time`), PostgreSQL
driver, `kotlinx-coroutines-reactor` (Kotlin `Flow` → SSE in Spring MVC), fabric8 `kubernetes-client`, `//k8s/crds`,
`//messaging`, Spring Modulith 2.0.5 (`spring-modulith-api` at compile time, `spring-modulith-starter-test` in tests).

## Configuration

| Key | Environment variable | Default | Notes |
|---|---|---|---|
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/sift` | |
| `spring.datasource.username` | `SPRING_DATASOURCE_USERNAME` | `sift` | |
| `spring.datasource.password` | `SPRING_DATASOURCE_PASSWORD` | `sift` | |
| `spring.rabbitmq.host` / `port` | `SPRING_RABBITMQ_HOST` / `SPRING_RABBITMQ_PORT` | `localhost` / `5672` | |
| `spring.rabbitmq.username` / `password` | `SPRING_RABBITMQ_USERNAME` / `SPRING_RABBITMQ_PASSWORD` | `sift` / `sift` | |
| `spring.rabbitmq.virtual-host` | `SPRING_RABBITMQ_VIRTUAL_HOST` | `/` | |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | `http://localhost:8180/realms/sift` | Issuer of accepted JWTs (any OIDC provider); OpenID configuration and JWKS are discovered from it, `iss` must match exactly. Default is the Compose Keycloak realm. |
| `spring.security.oauth2.resourceserver.jwt.audiences` | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES` | `sift-server` | Comma-separated values the `aud` claim must contain (Boot's built-in validator). Set to an empty string to disable the audience check. |
| `sift.server.auth.client-id` | `SIFT_SERVER_AUTH_CLIENT_ID` | `sift-web` | Public (PKCE) client id the web client authenticates as; only echoed through `GET /api/v1/auth/config`, not used for validation. Required (`@NotBlank`). |
| `sift.server.auth.scopes` | `SIFT_SERVER_AUTH_SCOPES` | `openid,profile,email` | Scopes the web client should request; echoed through `/api/v1/auth/config`. |
| `sift.server.auth.claims.username` | `SIFT_SERVER_AUTH_CLAIMS_USERNAME` | `preferred_username` | JWT claim read into `users.username`; falls back to `sub` when absent/blank. |
| `sift.server.auth.claims.email` | `SIFT_SERVER_AUTH_CLAIMS_EMAIL` | `email` | JWT claim read into `users.email` (`null` when absent). |
| `sift.server.namespace` | `SIFT_SERVER_NAMESPACE` | `sift-dev` | Namespace in which `CodeReview` CRs are applied; must be a DNS label. |
| `sift.server.encryption-key` | `SIFT_SERVER_ENCRYPTION_KEY` | — (required) | Base64 AES-256 key (exactly 32 bytes once decoded) used to encrypt repository tokens at rest; startup fails with a message naming the variable otherwise. |
| `sift.server.secret-prefix` | — | `sift-repo-` | Prefix of k8s Secrets created per repository (`<prefix><repository id>`). |
| `sift.server.watch.enabled` | — | `true` | `false` stops the `LISTEN sift_agent_runs` coroutine; the watch endpoint then only serves snapshots (integration tests default to `false`). |
| `sift.server.watch.heartbeat` | — | `15s` | Interval of the `:heartbeat` comment frames on the SSE run watch. |
| `sift.server.messaging.consumers-enabled` | — | `true` | `false` removes the `@RabbitListener` consumers entirely (used by tests without a broker). |

Fixed settings: `spring.exposed.generate-ddl=false`, `spring.exposed.show-sql=false`,
`spring.flyway.enabled=true`, `spring.mvc.problemdetails.enabled=true`, `spring.mvc.async.request-timeout=-1`
(SSE streams are open-ended and must not be timed out by the servlet container), RabbitMQ template retry
(3 attempts, 1s initial interval, multiplier 2), `spring.rabbitmq.listener.simple.acknowledge-mode=auto`,
`spring.rabbitmq.listener.simple.default-requeue-rejected=false` (a listener exception rejects the message
once and it is dead-lettered, see [Queues](#queues-and-dead-lettering)).

## Database schema

Schema is owned by Flyway (`V1__init.sql`, `V2__users.sql`); Exposed never generates DDL.

| Table | Purpose |
|---|---|
| `users` | One row per authenticated identity, keyed by the unique `(issuer, subject)`; `username`, `email`, `created_at`, `last_seen_at` are refreshed from the token on every request (`V2__users.sql`). |
| `repositories` | Registered repositories; `token_ciphertext`/`token_iv` hold the encrypted access token, `secret_name` the mirrored k8s Secret. |
| `agent_runs` | One row per agent execution (`kind`, `source`, `phase`, `spec jsonb`, CR identity `cr_name`/`cr_uid`/`generation`, timestamps, nullable `created_by` → `users.id`). Unique on `cr_uid`, indexed by `(phase, created_at desc)` and `(created_by, created_at desc)`. |
| `review_results` | Result per `execution_id` (repository/branch/commit, summary), linked to its `agent_run`. Indexed by `(repository_url, commit_sha)`. |
| `review_findings` | Findings per result (`file`, line range, `severity`, `category`, `message`, `suggestion`), cascade-deleted with the result. |

The trigger `agent_runs_notify` (function `notify_agent_run()`) emits
`pg_notify('sift_agent_runs', {id, phase, updatedAt})` after every insert/update on `agent_runs`; the
[watch](#agent-run-watch-sse) listener consumes that channel. Besides the Hikari pool the server holds one
extra Postgres connection per replica for `LISTEN` (`application_name = sift-server-watch`).

## Running locally

```shell
docker compose up -d --wait postgres rabbitmq keycloak
SIFT_SERVER_ENCRYPTION_KEY=$(openssl rand -base64 32) ./kotlin run --module server
```

Health: `GET http://localhost:8080/actuator/health` (liveness/readiness probes under
`/actuator/health/liveness` and `/actuator/health/readiness`; `info` is exposed as well). These and
`GET /api/v1/auth/config` need no token; everything else does. The defaults accept tokens from the Compose
Keycloak realm `sift` (`http://localhost:8180/realms/sift`, admin console `http://localhost:8180`, `admin`/`admin`),
which ships the dev user `dev`/`dev`. Obtain a token for manual calls with the password grant:

```shell
TOKEN=$(curl -s -d grant_type=password -d client_id=sift-web -d username=dev -d password=dev \
  -d 'scope=openid profile email' http://localhost:8180/realms/sift/protocol/openid-connect/token | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/me
```

Access tokens of the dev realm live 5 minutes. Keycloak needs 10–40 s on a cold start; `--wait` blocks until
its health check passes. The realm is imported with `IGNORE_EXISTING`, so after editing
`config/keycloak/sift-realm.json` recreate the container (`docker compose rm -sf keycloak && docker compose up -d --wait keycloak`).

Tests: `./kotlin test -m server` — `PostgresIntegrationTest` subclasses start a `postgres:17-alpine`
Testcontainer (and `RabbitMqIntegrationTest` subclasses additionally a `rabbitmq:4-management-alpine` one);
they require a running Docker daemon.

The host-run server needs a kubeconfig (`KUBECONFIG=$PWD/.kubeconfig` for the kind cluster) with rights to
create `CodeReview` CRs and Secrets in `sift.server.namespace`; see
[local kind development](local-kind-development.md#running-the-server-locally).

## Deployment

`k8s/manifests/server/` contains the namespace-scoped (`sift-dev`) manifests, labelled like the operator
manifests (`app.kubernetes.io/managed-by: sift-local-dev`):

| File | Contents |
|---|---|
| `rbac.yaml` | ServiceAccount `sift-server`, Role (`get`/`list`/`create`/`delete` on `sift.org codereviews`; `get`/`create`/`patch`/`update`/`delete` on `secrets`) and RoleBinding. |
| `configmap.yaml` | ConfigMap `sift-server` with the non-secret environment: `SIFT_SERVER_NAMESPACE=sift-dev`, `SPRING_DATASOURCE_URL` (`jdbc:postgresql://postgres:5432/sift`), `SPRING_DATASOURCE_USERNAME`, `SPRING_RABBITMQ_HOST/PORT/USERNAME/VIRTUAL_HOST` (`rabbitmq:5672`), `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` (`http://host.docker.internal:8180/realms/sift`, the Compose Keycloak as seen from a kind cluster), `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES=sift-server`, `SIFT_SERVER_AUTH_CLIENT_ID=sift-web`, `SIFT_SERVER_AUTH_SCOPES`. Adjust the hosts to the Postgres/RabbitMQ Services and the issuer/client id to the organisation's identity provider. Note that the token's `iss` must equal the configured issuer, so browser clients and the server must use the same issuer URL. |
| `deployment.yaml` | Deployment `sift-server`, 1 replica (one `LISTEN` connection per replica; multi-replica fan-out is untested), `serviceAccountName: sift-server` with token automount, `envFrom` the ConfigMap, passwords/key from Secret `sift-server-secrets`, container port `8080`, startup/liveness probe `/actuator/health/liveness`, readiness `/actuator/health/readiness`, requests `250m`/`512Mi`, limits `1`/`1Gi`, nonroot (`10001`), `RuntimeDefault` seccomp, read-only root filesystem with an `emptyDir` on `/tmp`, all capabilities dropped. The image `jbfpietzko/sift-server:latest` is a placeholder until a server image is published. |
| `service.yaml` | ClusterIP Service `sift-server`, port `8080` → `http`. No Ingress is shipped; TLS termination in front of the server remains the operator's responsibility ([ADR 0016](../adrs/0016-oauth2-resource-server-and-user-attribution.md)). |

The Secret is created by the administrator and is not part of the repository:

```shell
kubectl --kubeconfig "$PWD/.kubeconfig" -n sift-dev create secret generic sift-server-secrets \
  --from-literal=datasource-password="$PGPASSWORD" \
  --from-literal=rabbitmq-password="$RABBITMQ_PASSWORD" \
  --from-literal=encryption-key="$(openssl rand -base64 32)"
```

Keys map to `SPRING_DATASOURCE_PASSWORD`, `SPRING_RABBITMQ_PASSWORD` and `SIFT_SERVER_ENCRYPTION_KEY`; authentication
needs no Secret entry because the web client is a public PKCE client and the server holds no client secret. Losing
or rotating `encryption-key` makes stored repository tokens undecryptable; they must then be re-entered via
`PUT /api/v1/repositories/{id}`. Validate and apply:

```shell
kubectl --kubeconfig "$PWD/.kubeconfig" apply --dry-run=client -f k8s/manifests/server/
kubectl --kubeconfig "$PWD/.kubeconfig" apply -f k8s/manifests/server/
```

The manifests assume the `codereviews.sift.org` CRD is installed and the `sift-dev` namespace exists (see
[local kind development](local-kind-development.md)). Flyway migrates the schema on startup, so the
database user must own the `sift` database.

## Out of scope

Not part of this iteration; each is a documented follow-up:

- **Authorisation model** — authentication is in place ([Security](#security)) but every authenticated user may
  do everything; roles/permissions, creator-only cancel and quotas are a follow-up ADR
  ([ADR 0016](../adrs/0016-oauth2-resource-server-and-user-attribution.md) consequences). Opaque-token
  introspection is not supported (JWTs only).
- **Security-review agent kind** — `AgentKind` only has `CODE_REVIEW`; a second `AgentKindAdapter` and CRD
  are needed for the security scanner.
- **VCS adapter** — no webhook ingestion (PR created / comment on thread) and no posting of findings back to
  GitHub/GitLab/Codeberg; runs are started via the API only.
- **MCP endpoint** for coding agents.
- **CR reconciliation** — a missed `code-review.status` event is not repaired by re-reading CRs
  ([ADR 0011](../adrs/0011-operator-status-events-via-rabbitmq.md) consequence).
- **Server container image and publication**; the Deployment references a placeholder tag.
- **Multi-replica SSE watch** validation and any UI.

## Security

The server is an OAuth2 **resource server** ([ADR 0016](../adrs/0016-oauth2-resource-server-and-user-attribution.md)):
it validates JWT bearer tokens issued by any OIDC provider and never performs a login itself. The web client
runs the authorization-code + PKCE flow as a public client and sends the resulting access token; the server
holds no client secret.

| Aspect | Behaviour |
|---|---|
| Protected surface | Everything, including `/api/**`, except `GET /actuator/health`, `/actuator/health/**`, `/actuator/info` and `GET /api/v1/auth/config` (`SecurityConfiguration.ANONYMOUS_GET_PATHS`). |
| Token validation | `spring-boot-starter-oauth2-resource-server`: signature via the JWKS discovered from `issuer-uri`, `iss` equality, `exp`/`nbf`, and — when `audiences` is non-empty — that `aud` contains one of the configured values. No custom validators. |
| Missing/invalid token | `401` with `WWW-Authenticate: Bearer` (plus RFC 6750 `error`/`error_description` for malformed or rejected tokens) and an `application/problem+json` body `{type, title: "Unauthorized", status: 401, detail, instance}` written by `ProblemDetailAuthenticationEntryPoint`. Expired, forged, wrong-issuer and wrong-audience tokens are all `401`. |
| Access denied | `403` problem from `ProblemDetailAccessDeniedHandler` (not reachable today because authorization is flat). |
| Session/CSRF | Stateless (`SessionCreationPolicy.STATELESS`), CSRF disabled — there is no cookie to protect. |
| Authorization | Flat: any authenticated user may use every endpoint. |
| User provisioning | `UserProvisioningFilter` (after `BearerTokenAuthenticationFilter`) calls `UserService.provision(jwt)` on every authenticated request: `INSERT … ON CONFLICT (issuer, subject) DO UPDATE SET username, email, last_seen_at`. The row is exposed to controllers via `@CurrentUser user: User`. |
| Claims | `users.username` ← `sift.server.auth.claims.username` (default `preferred_username`, fallback `sub`); `users.email` ← `sift.server.auth.claims.email` (default `email`, may be `null`). |
| Logging | Tokens are never logged or echoed; `AuthConfigResponse` contains no secret. |

### `GET /api/v1/auth/config` (anonymous)

Discovery for the SPA's PKCE setup, taken from configuration only:

```json
{ "issuerUri": "http://localhost:8180/realms/sift", "clientId": "sift-web", "scopes": ["openid", "profile", "email"] }
```

The client fetches the provider's OpenID configuration from `issuerUri`, starts the authorization-code flow
with `clientId` and `scopes`, and sends the access token as `Authorization: Bearer …` on every API call,
including the SSE watch (a native `EventSource` cannot set headers; use a fetch-based SSE client).

### `GET /api/v1/me`

Returns the caller as provisioned from the current token:
`UserResponse { id, subject, issuer, username, email }`. `id` is the server-side UUID used in
`AgentRunResponse.createdBy.id` and the `createdBy` query parameter.

### Identity providers

Only the issuer, the client id and (optionally) the audience differ between providers. For the Compose
Keycloak (`config/keycloak/sift-realm.json`) the realm `sift` contains the public client `sift-web`
(standard flow, PKCE `S256`, redirect URIs `http://localhost:*` / `http://127.0.0.1:*`, web origins `+`, direct
access grants enabled for the e2e harness) with an audience mapper adding `aud=sift-server`, and the users
`dev`/`dev` and `e2e`/`e2e`. For another provider create an equivalent public client, make sure the access
token carries the configured audience (or set `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES` to an empty
string) and adjust `SIFT_SERVER_AUTH_CLAIMS_USERNAME`/`_EMAIL` if its claims differ (e.g. Entra ID puts the
login name in `preferred_username` as well, Auth0 typically in `nickname`).

## Repositories API

Base path `/api/v1/repositories`, JSON in/out, errors as RFC 7807 `application/problem+json`.

| Method | Path | Request | Response |
|---|---|---|---|
| `POST` | `/` | `CreateRepositoryRequest { name (required, ≤100), url (required), token? }` | `201 Created`, `Location: /api/v1/repositories/{id}`, `RepositoryResponse` |
| `GET` | `/` | — | `200`, `RepositoryResponse[]` sorted by name |
| `GET` | `/{id}` | — | `200`, `RepositoryResponse` |
| `PUT` | `/{id}` | `UpdateRepositoryRequest { url?, token?, clearToken? = false }` | `200`, `RepositoryResponse` |
| `DELETE` | `/{id}` | — | `204 No Content` |

`RepositoryResponse { id, name, url, hasToken, secretName, createdAt, updatedAt }` — the token is never
returned. Omitted/blank `token` on `POST` registers a repository without credentials; on `PUT` an omitted
`token` keeps the current one, a non-blank `token` rotates it and `clearToken: true` removes it (combining
`clearToken` with a new token is a `400`).

Validation: `name` is trimmed and must be unique; `url` must parse as an `http(s)` URI with a host and
without userinfo (embedded credentials), query, fragment, whitespace or `${` placeholders — the same rules
the operator applies to `CodeReview.spec.repositoryUrl`.

| Status | Cause |
|---|---|
| `400` | Bean validation failure (`MethodArgumentNotValidException`), unreadable body, malformed UUID, invalid URL, `clearToken` + `token`. |
| `404` | Unknown repository id. |
| `409` | Duplicate `name` on create; delete while a `RepositoryUsageCheck` vetoes — the `agents` module does so while the repository still has `agent_runs` in a non-terminal phase (not `SUCCESS`/`FAILED`/`CANCELLED`). |

### Token encryption and Secret sync

- `TokenCipher` encrypts with AES/GCM/NoPadding, a fresh 12-byte `SecureRandom` IV per call and a 128-bit
  tag; ciphertext and IV are stored in `repositories.token_ciphertext` / `token_iv`.
- When a token is present, `RepositorySecretSync` server-side-applies (field manager `sift-server`, force
  conflicts) an Opaque Secret `sift-repo-<id>` in `sift.server.namespace` with `stringData.token` and labels
  `app.kubernetes.io/managed-by: sift-server`, `sift.org/repository-id: <id>`; `repositories.secret_name`
  records it. Rotation re-applies, clearing/deleting removes the Secret (a missing Secret is not an error).
- `RepositoryService.secretRef(id)` returns `SecretRef(name, key = "token")` (or `null`) for the Step 4 CR
  builder, which places it in `CodeReview.spec.credentialsSecretRef`; the operator injects it as
  `SIFT_REVIEW_AUTH_TOKEN`. There is no API to decrypt or read a stored token.
- Required RBAC for the server ServiceAccount: `get`, `create`, `patch`, `delete` on `secrets` in its
  namespace.

## Agent runs API

Base path `/api/v1/agents`, JSON in/out, errors as RFC 7807 `application/problem+json`. An agent run is
the server-side record of one agent execution; for `CODE_REVIEW` it is backed by a `CodeReview` CR.

| Method | Path | Request | Response |
|---|---|---|---|
| `POST` | `/` | `CreateAgentRunRequest { kind, repositoryId, branch, baseBranch, commitSha, pullRequest? }` | `202 Accepted`, `Location: /api/v1/agents/{id}`, `AgentRunResponse` with `createdBy` = the caller |
| `GET` | `/` | query `kind?`, `phase?`, `repositoryId?`, `mine = false`, `createdBy?` (user UUID), `page = 0`, `size = 20` (clamped to 1..200) | `200`, `PageResponse<AgentRunResponse> { items, page, size, total }`, newest first |
| `GET` | `/{id}` | — | `200`, `AgentRunResponse` |
| `POST` | `/{id}/cancel` | — | `202 Accepted`, `AgentRunResponse` (phase `CANCELLED`) |
| `GET` | `/watch` | query `agentId?`, `kind?`, `mine = false`, `createdBy?`; header `Last-Event-ID?` | `200`, `text/event-stream` — see [Agent run watch](#agent-run-watch-sse) |

`{id}` is restricted to the UUID pattern `[0-9a-fA-F-]{36}`, so `/watch` never falls into the run-by-id route
(a request to `/watch` without `Accept: text/event-stream` gets `406`, not `400`).

`AgentRunResponse { id, kind, source, repositoryId, crName, crUid, generation, executionId, phase, reason,
message, spec, createdAt, startedAt, completedAt, updatedAt, createdBy }`. `spec` is the JSON persisted in
`agent_runs.spec`: `{ repositoryUrl, branch, baseBranch, commitSha, pullRequest }`. `createdBy` is
`{ id, username } | null` — the `users` row of the API caller that created the run (`username` is read live
via a left join, so it follows the user's current claim), `null` for `EXTERNAL` runs and for runs created
before `V2__users.sql`.

Creator filters: `mine=true` restricts the page to runs created by the caller (`@CurrentUser`), `createdBy=<user
id>` to runs of that user (e.g. an id taken from another run's `createdBy.id` or from `/api/v1/me`). Both may be
combined only when they name the same user; `mine=true&createdBy=<other>` is a `400`
(`AgentRunFilter.resolveCreatedBy`). All filters combine with `AND`; `mine=true` for a user without runs yields
an empty page.

Enums: `AgentKind { CODE_REVIEW }`, `RunSource { API, EXTERNAL }` (`EXTERNAL` = first seen through a status
event, e.g. a CR applied with `kubectl`; such runs have no `repositoryId`), `AgentPhase { CREATED, PENDING,
RUNNING, SUCCESS, FAILED, CANCELLED }` — the last three are terminal.

Validation: `branch`/`baseBranch` not blank, `commitSha` must match `^[0-9a-fA-F]{40}$`, `kind` and
`repositoryId` must parse.

| Status | Cause |
|---|---|
| `400` | Bean validation failure, unreadable body, malformed UUID/enum in body or query, `mine=true` combined with a different `createdBy`, unknown agent kind adapter. |
| `404` | Unknown run id; a path `{id}` that is not UUID-shaped (no route matches); unknown `repositoryId` on create. |
| `409` | Cancel of a run that is already terminal. |
| `500` | Applying the CR failed; the run stays visible with phase `FAILED`, reason `ApplyFailed` and the client exception message. |

### Create and cancel flow

1. `AgentRunService.create(request, user)` validates the repository (`RepositoryService.get`), inserts the run as
   `CREATED`/`API` with `crName = cr-<run id>`, `created_by = user.id` and the spec JSON, and commits
   (`TransactionOperations`).
2. `CodeReviewAdapter.apply` builds a `CodeReview` in `sift.server.namespace` named `cr-<run id>` with labels
   `app.kubernetes.io/managed-by: sift-server`, `sift.org/run-id`, `sift.org/repository-id`, `spec` from the
   request plus the repository URL and `credentialsSecretRef` from `RepositoryService.secretRef` (omitted when
   the repository has no token), and `create()`s it. The returned `metadata.uid` is stored as `cr_uid`.
3. If the apply throws, the run is updated to `FAILED`/`ApplyFailed` in its own transaction and the exception
   is rethrown (→ `500`).
4. `cancel` (`@Transactional`) rejects terminal runs with `409`, deletes the CR with
   `propagationPolicy=Foreground` (a missing CR is not an error) and sets `CANCELLED`, reason
   `CancelledByUser`, `completedAt = now`.

Adapters implement `AgentKindAdapter { kind, resourceName(runId), apply(run, request), delete(run) }`; the
service picks the adapter by `request.kind`.

### Status upsert rules

`AgentStatusConsumer` receives `CodeReviewStatusChangedEvent`s and calls `AgentRunService.applyStatus`
(`@Transactional`), which looks the run up by `cr_uid = event.reviewUid`:

- Unknown phase string (not an `AgentPhase`) → warn and skip.
- No run for the uid → insert an `EXTERNAL` `CODE_REVIEW` run (`crName = reviewName`, spec from the event,
  `repositoryId = null`, `createdBy = null`) with the event's phase/reason/message/generation/executionId/timestamps.
- Stored phase terminal → skip (a `CANCELLED` run is never resurrected).
- `event.generation < stored.generation`, or `stored.observedAt != null && event.observedAt <= stored.observedAt`
  → skip (out-of-order or duplicate delivery).
- Otherwise update `phase`, `reason`, `message`, `generation`, `executionId`, `startedAt`, `completedAt`,
  `observedAt` and `updatedAt = now`; `cr_name`, `spec` and the CR identity are left untouched.

Every write goes through the `agent_runs_notify` trigger, so the watch sees API and event-driven changes
alike.

### Queues and dead-lettering

`MessagingConfiguration` (server) declares, via a `Declarables` bean picked up by `RabbitAdmin`, on top of the
`sift.events` topic exchange from `//messaging`:

| Declarable | Details |
|---|---|
| exchange `sift.events.dlx` | durable topic exchange |
| queue `sift.server.dead-letter` | durable, bound to `sift.events.dlx` with `#` |
| queue `sift.server.code-review.status` | durable, `x-dead-letter-exchange: sift.events.dlx`, bound to `sift.events` with `code-review.status` |
| queue `sift.server.code-review.completed` | durable, `x-dead-letter-exchange: sift.events.dlx`, bound to `sift.events` with `code-review.completed` (`ReviewResultConsumer`) |

Names are constants in `ServerQueues`. With `acknowledge-mode=auto` and `default-requeue-rejected=false` a
listener exception nacks the message without requeue, so it lands in `sift.server.dead-letter` instead of
looping. Payloads are deserialised by the shared `JacksonJsonMessageConverter` into the listener parameter
type (inferred type precedence), independent of the publisher's `__TypeId__` header.

## Agent run watch (SSE)

`GET /api/v1/agents/watch` (`Accept: text/event-stream`) streams agent run changes as Server-Sent Events
([ADR 0013](../adrs/0013-agent-run-watch-via-pg-notify.md)).

| Parameter | Effect |
|---|---|
| `agentId` (query, UUID) | Only this run: the snapshot is that run (if it exists) and live events are filtered to it. |
| `kind` (query, `AgentKind`) | Only runs of this kind (snapshot and live). |
| `mine` (query, boolean, default `false`) | Only runs created by the caller (snapshot and live), like the list endpoint. |
| `createdBy` (query, user UUID) | Only runs created by that user; `mine=true` with a different `createdBy` is a `400`. |
| `Last-Event-ID` (header, epoch millis) | Resume: the snapshot contains only runs with `updated_at` after this instant. Malformed values are ignored (full snapshot). |

The stream requires a bearer token like every other endpoint; it is authenticated once at connect time, so an
open stream is not cut when the access token later expires — the next reconnect needs a fresh token. Browsers'
native `EventSource` cannot set an `Authorization` header; use a fetch-based SSE client.

Wire format — every data frame is a complete run, never a delta:

```
id: 1788775200000
event: SNAPSHOT
data: {"type":"SNAPSHOT","run":{ ...AgentRunResponse... }}

:heartbeat

id: 1788775260000
event: UPDATED
data: {"type":"UPDATED","run":{ ...AgentRunResponse... }}
```

- `id` is the run's `updatedAt` as epoch milliseconds, `event` is `SNAPSHOT` or `UPDATED`; `data` is
  `AgentRunEvent { type, run: AgentRunResponse }`.
- On connect the server subscribes to live events first and then sends `SNAPSHOT` frames (newest 200 runs
  matching the filter, or the runs updated since `Last-Event-ID`, or the single `agentId` run). A write that
  lands during the snapshot is delivered as well — possibly the same state twice. An empty snapshot sends no
  frame.
- `UPDATED` frames follow every committed insert/update of a matching `agent_runs` row (API create/cancel,
  status events from the operator) via trigger → `NOTIFY` → `PgNotificationListener` → `AgentRunEvents`.
- A comment frame `:heartbeat` is sent every `sift.server.watch.heartbeat` (15s) while the stream is idle so
  proxies keep the connection open; browsers' `EventSource` ignores comments.
- Reconnect semantics: `EventSource` reconnects automatically and sends the last `id` as `Last-Event-ID`;
  the server replays runs changed since then as `SNAPSHOT`. Live delivery is at-most-once (`NOTIFY` is not
  durable and a slow subscriber drops the oldest of 256 buffered events), so clients must treat frames as
  "the run is now X" and rely on resume rather than on gap-free deltas.
- Server side, the listener runs in the `ApplicationCoroutineScope` from `ApplicationReadyEvent` until the
  context closes; the `LISTEN` connection is not taken from the pool, reconnects with exponential backoff
  (500ms → 30s, reset after a successful connect) and is visible in `pg_stat_activity` as
  `sift-server-watch`. `sift.server.watch.enabled=false` disables it. `spring.mvc.async.request-timeout=-1`
  keeps the async servlet request open indefinitely.

## Review results API

Base path `/api/v1/results`, read-only, JSON out, errors as RFC 7807 `application/problem+json`. A review
result is the persisted `CodeReviewCompletedEvent` of one agent execution together with its findings; results
are only ever created by the [`code-review.completed` consumer](#ingestion-idempotency-and-run-promotion).

| Method | Path | Request | Response |
|---|---|---|---|
| `GET` | `/` | query `repositoryUrl?`, `commitSha?`, `agentRunId?` (UUID), `page = 0`, `size = 20` (clamped to 1..200) | `200`, `PageResponse<ReviewResultSummaryResponse> { items, page, size, total }`, most recently completed first |
| `GET` | `/{id}` | — | `200`, `ReviewResultResponse` (with all findings) |
| `GET` | `/{id}/findings` | query `severity?` (`BLOCKER`, `MAJOR`, `MINOR`, `INFO`), `file?` (exact path match) | `200`, `ReviewFindingResponse[]` in insertion order |

`ReviewResultSummaryResponse { id, executionId, agentRunId, repositoryUrl, branch, baseBranch, commitSha,
pullRequest, summary, findingCount, completedAt, receivedAt }` — `findingCount` comes from one grouped count
query over the page (`ReviewResultRepository.countFindings`). `ReviewResultResponse` has the same fields plus
`findings: ReviewFindingResponse[]`. `ReviewFindingResponse { id, file, startLine, endLine, severity, category,
message, suggestion }`; `severity` is the `org.sift.events.Severity` enum. `agentRunId` is `null` when no run
carried the event's `executionId` at ingestion time (e.g. a CR applied outside the server whose status event
was never seen). All filters are exact, case-sensitive equality and combine with `AND`.

| Status | Cause |
|---|---|
| `400` | Malformed UUID or unknown `severity` in path/query. |
| `404` | Unknown result id (both `/{id}` and `/{id}/findings`). |

### Ingestion, idempotency and run promotion

`ReviewResultConsumer` (`@RabbitListener` on `sift.server.code-review.completed`, gated like the status
consumer by `sift.server.messaging.consumers-enabled`) hands every `CodeReviewCompletedEvent` to
`ReviewResultService.store` (`@Transactional`):

1. The agent run is looked up by `agent_runs.execution_id = event.executionId` (newest run wins should several
   carry the same id); its `id` becomes `review_results.agent_run_id`, or `null` when there is none.
2. The result is written with `INSERT … ON CONFLICT DO NOTHING` on the unique `execution_id` (Exposed
   `insertIgnore`), `completed_at` from the event and `received_at = now`; findings are batch-inserted only
   when the result row was actually inserted. A redelivered or duplicate event therefore changes nothing and is
   logged at debug — the first delivery wins, later payloads for the same `executionId` are discarded.
3. If the result was inserted and a run is linked, `AgentRunService.completeWithResult(runId, completedAt)` promotes
   it — provided it is still non-terminal (`CREATED`/`PENDING`/`RUNNING`) — to `SUCCESS`, reason `ResultReceived`,
   `message = null`, `completedAt = event.completedAt`, `observedAt = updatedAt = now`; `generation` and
   `executionId` are kept.
   The agent has evidently finished even if the operator's final status event is late or lost, and the write
   fires `agent_runs_notify`, so watchers see the `SUCCESS` frame. Terminal runs (including `CANCELLED`) are
   never touched, and a later `code-review.status` event for the run is skipped by the terminal-phase rule.

Both writes share one transaction; a failure rolls back result and promotion together and the message is
dead-lettered as described in [Queues](#queues-and-dead-lettering).
