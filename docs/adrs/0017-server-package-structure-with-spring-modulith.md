# ADR 0017: Server package structure enforced with Spring Modulith

Date: 2026-09-08

## Status

Accepted.

## Context

The `server` module grew feature by feature: each top-level package (`agents`, `repositories`,
`results`, `users`, `security`, `watch`) mixed controllers, DTOs, Exposed tables, repositories,
services, RabbitMQ consumers and Kubernetes adapters in one flat namespace, and the packages reached
freely into each other's persistence layer (`RepositoryRepository` queried `AgentRunsTable`,
`ReviewResultService` wrote `agent_runs` through `AgentRunRepository`, `AgentRunsTable` declared foreign
keys against `RepositoriesTable` and `UsersTable`). `watch` was a separate package although everything
in it is about agent runs, `security` and `users` referenced each other, and `Page`/`PageResponse` lived
in `agents` while being used by `results`.

Nothing enforced any of this, so the drift would continue. Two options were considered to make the
structure explicit and machine-checked:

- hand-written ArchUnit rules (layer/package rules per module), or
- [Spring Modulith](https://spring.io/projects/spring-modulith), which derives application modules
  from the package layout, verifies encapsulation and dependency cycles with ArchUnit under the hood
  and can render the resulting structure as C4/PlantUML documentation.

## Decision

- **Each direct sub-package of `org.sift.server` is a Spring Modulith application module**:
  `api`, `config`, `security`, `users`, `repositories`, `agents`, `results`. A module's base package is
  its public API (domain model, service, SPI types); the technical parts live in sub-packages that are
  internal to the module: `persistence` (Exposed tables + repositories), `web` (controllers + DTOs),
  `messaging` (`@RabbitListener`s), `adapters` (Kubernetes CR adapters), `secrets` (token cipher and
  Secret sync) and `watch` (the SSE watch, folded into `agents`).
- **Dependencies are declared, not discovered.** Every module carries a `ModuleMetadata` class annotated
  with `@ApplicationModule(allowedDependencies = …)` (Kotlin has no `package-info.java`):

  | Module | May depend on |
  |---|---|
  | `api`, `config` | — |
  | `users` | `api`, `config` |
  | `repositories` | `api`, `config` |
  | `security` | `config`, `users` |
  | `agents` | `api`, `config`, `repositories`, `users`, `users :: persistence` |
  | `results` | `api`, `config`, `agents` |

  `users :: persistence` is the only named interface: `agents` left-joins `UsersTable` to carry the
  creator's username with each run. Everything else crosses module boundaries through services.
- **Cycles were broken at the seams, not papered over:**
  - `repositories` ↔ `agents`: `repositories` exposes the SPI `RepositoryUsageCheck`; `agents`
    implements it (`AgentRunRepositoryUsageCheck`) to veto deleting a repository with active runs.
    `RepositoryService.delete` no longer knows about `agent_runs`.
  - `results` → `agents.persistence`: `results` calls `AgentRunService.findByExecutionId` and
    `AgentRunService.completeWithResult` instead of the repository; the promotion rules live in the
    `agents` module.
  - `security` ↔ `users`: `@CurrentUser` and `UserProvisioningFilter` moved to `users`; `security`
    only wires the filter into the chain.
  - Exposed `references(...)` across modules were dropped; foreign keys are owned by the Flyway schema
    and the one cross-module join states its condition explicitly.
- **Verification is a unit test**, `ModularityTest`: `ApplicationModules.of(Application::class.java).verify()`
  plus a check of the expected module set and a `Documenter` run that writes C4 component diagrams and
  per-module AsciiDoc to `server/build/spring-modulith-docs`. Only `spring-modulith-api` (annotations) is
  on the production classpath; `spring-modulith-starter-test` is test-only, so nothing changes at runtime.
- Test sources are excluded from verification explicitly (ArchUnit only recognises Maven/Gradle test
  output directories, not the Kotlin toolchain's) because test fixtures such as `TestTokens`,
  `TestUsers` and `PostgresIntegrationTest` are shared across modules on purpose.

## Consequences

- Adding a new feature means adding a module (or extending one) and stating its dependencies up front;
  `./kotlin check` fails on an undeclared dependency, a cycle or a reference to another module's
  `persistence`/`web`/… internals.
- The `agents` module now owns the whole run lifecycle including the watch stream and the "result
  received" promotion; `results` is a pure consumer of its API.
- Cross-module data access is visible in one place (`users :: persistence`). Should it grow, the
  alternative is to resolve usernames through `UserService` and drop the named interface.
- The module layout is documented in [server](../system-components/server.md#module-layout); the
  generated diagrams are build output and not committed.
- Not adopted: Spring Modulith's runtime features (module-aware event publication registry, actuator
  endpoint, `@ApplicationModuleTest` slices). RabbitMQ remains the integration mechanism between
  components ([ADR 0001](0001-use-rabbitmq-as-message-queue.md)); in-process module events can be
  revisited if the server ever needs asynchronous intra-module workflows.
