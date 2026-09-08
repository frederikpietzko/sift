# Sift API contract

`openapi.yaml` is the OpenAPI 3.1 description of the Sift server's public API (`/api/v1/**`, REST and the
`GET /api/v1/agents/watch` Server-Sent Events stream). It is the shared, machine-readable contract that clients are
generated from: the web UI (`web/packages/api-client`) today, the VSCode and IntelliJ plugins later.

## Source of truth

The file is **generated, not hand-written**. springdoc renders it from the Spring controllers in `server/` and the
server serves it anonymously at `GET /v3/api-docs.yaml` (JSON at `/v3/api-docs`). Everything that shapes the
output lives in the server:

- `server/src/org/sift/server/api/OpenApiConfiguration.kt` – info block, `bearerAuth` JWT security scheme applied to
  every operation, stable `operationId`s (`<resource><Method>`, e.g. `agentRunList`, `repositoryCreate`), the shared
  `ProblemDetail` schema and uniform `application/problem+json` error responses.
- `@Tag` / `@Operation` / `@ApiResponse` annotations on the controllers for summaries and non-default status codes.
- `springdoc.*` in `server/resources/application.yaml` (`/api/**` only, ordered keys, Swagger UI disabled).

The `servers:` block is stripped because it echoes the host the description was requested from.

## Drift guard

`server/test/org/sift/server/api/OpenApiContractTest.kt` fetches the live description and compares it with this
file. Any difference fails `./kotlin check` with a unified diff, so a controller or DTO change is never merged
without its contract update.

## Regenerating

```shell
SIFT_UPDATE_OPENAPI=true ./kotlin test -m server --include-classes org.sift.server.api.OpenApiContractTest
```

Review the diff of `api/openapi.yaml`, regenerate the TypeScript types in `web/packages/api-client`
(`pnpm --filter api-client generate`) and commit everything together.

## Conventions

- Errors are RFC 9457 problem details (`ProblemDetail`, `application/problem+json`); every secured operation
  documents `401`, and `400`/`404`/`409` are declared where the server produces them.
- Enums (`AgentKind`, `AgentPhase`, `RunSource`, `Severity`, `AgentRunEventType`) are rendered inline on the fields
  that use them.
- `AgentRunResponse.spec` is a free-form object whose shape depends on `kind` (`CodeReviewRunSpec` for
  `CODE_REVIEW`).
- The SSE stream is documented as a `text/event-stream` response whose `data` frames are `AgentRunEvent`s; event
  names equal `AgentRunEvent.type`, event ids are `updatedAt` epoch millis and are accepted back as `Last-Event-ID`.
