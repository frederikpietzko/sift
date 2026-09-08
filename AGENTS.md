# Project Intent

Open Source Agentic Code Review Platform designed for self hosting and deployment on k8s.

# Tooling

Kotlin toolchain (formerly Amper), use `./kotlin --help` to explore usage. The web workspace (`web/`) is built with
pnpm (Node 22, `web/.nvmrc`), not by the Kotlin toolchain.

# Architecture & Stack

Kotlin Toolchain multi-module build.

- build-config: custom plugins for Kotlin Toolchain
    - crd-generator: fabric8 crd generator to generate k8s CRDs into `k8s/manifests/crds`
    - detekt: linting tool to enforce code quality
- k8s/crds: Kotlin, Definition of CRDs as Kotlin classes with fabric8 apt
- k8s/manifests: YAML, Kubernetes manifests generated from CRDs, future home for k8s manifests for deployments, roles,
  service account, helm charts, etc.
- k8s/local: potentially helpful tools for testing operator in k8s locally
- k8s/operator: Future -> Kotlin, Spring Boot 4, java-operator-sdk, k8s-operator to manage agent jobs
- agents: Kotlin, Spring Boot 4, Spring AI; different agents to perform actions to be run as k8s jobs
    - shared: Kotlin, Spring AI; Shared code for agents, eg common advisors
    - code-review: Kotlin, Spring Boot 4, Spring AI; agent to perform code review
    - security-review: Kotlin, Spring Boot 4, Spring AI; agent to perform security review
- events: Kotlin; shared event contracts (`SiftEvent`, `CodeReviewCompletedEvent`, `CodeReviewStatusChangedEvent`)
- messaging: Kotlin, Spring AMQP; shared RabbitMQ publisher/auto-configuration used by agents, operator and server
- server: Kotlin, Spring Boot 4, fabric8-client; applies CRs to cluster, exposes API to FE; its controllers are the
  source of the OpenAPI contract (springdoc, `GET /v3/api-docs.yaml`)
- api: `openapi.yaml`, the generated and committed OpenAPI 3.1 contract of `/api/v1/**`; drift fails
  `OpenApiContractTest` in `./kotlin check`; consumed by `web/packages/api-client` and later by IDE plugins
  (see `api/README.md`, `docs/adrs/0018-shared-openapi-contract-and-web-ui.md`)
- web: pnpm workspace (TypeScript); see `web/README.md`, `docs/system-components/web-ui.md`
    - packages/api-client: framework-agnostic typed client (`openapi-typescript` types generated from `api/openapi.yaml`,
      `openapi-fetch`, fetch-based SSE reader); reusable by the future VSCode/IntelliJ plugins
    - apps/web: React 19 + Vite SPA (Tailwind v4, shadcn/ui, TanStack Router/Query, `oidc-client-ts` PKCE); screens
      Runs (live via SSE, trigger, cancel), Repositories, Results; packaged as the `sift-web` nginx image
      (`apps/web/Dockerfile`, `nginx.conf`) that proxies `/api/` to the server, manifests in `k8s/manifests/web`
- e2e: Kotlin, JUnit 5, Testcontainers/kind; gated end-to-end scenario (`SIFT_E2E=true`) that spins up a kind cluster,
  deploys operator + agents and runs a real code review; see `docs/system-components/e2e-tests.md`
- tools: helper scripts for local development, eg `tools/dev.sh` (whole stack locally, defaults in the committed
  `tools/dev.env`) and `tools/e2e.sh` (secrets live in the git-ignored `tools/e2e.env`)
- Not started: VSCode and IntelliJ plugins (inputs are ready: `api/openapi.yaml`, `web/packages/api-client`)

# Tooling & Execution

- **Build System**: Use `./kotlin` for build and run tasks
- **Linting**: Use `./kotlin check detekt`
- **Tests**: Use `./kotlin test` or `./kotlin check test`
- **Checks**: Use `./kotlin check` to run all checks
- **E2E Tests**: Use `tools/e2e.sh` (sources credentials from `tools/e2e.env` automatically, sets `SIFT_E2E=true`, runs
  `./kotlin check tests --module e2e`). Flags: `--destroy` (delete kind cluster afterwards),
  `--pr owner/repo#N`, `--timeout 45m`, `-- <extra args>` forwarded to `./kotlin check`. Takes ~1 min. If
  `tools/e2e.env` is missing, copy `tools/e2e.env.example` and ask the user for the tokens; never commit it.
- **Local stack**: Use `tools/dev.sh` to start everything at once (Compose infra, operator, server, Vite dev server)
  with the committed development defaults from `tools/dev.env`; Ctrl-C stops the processes it started.
  Flags: `--no-operator` (no kind cluster needed), `--no-web`, `--no-server`, `--only <compose|operator|server|web>`,
  `--context kind-…`, `--stop` (stop the Compose services). Logs land in `build/dev/*.log`. Do NOT run it in the
  foreground of an agent session — it blocks; use `--only …` for targeted checks.
- **k8s**: k8s kind cluster running, kubeconfig at `./.kubeconfig`
- **api contract**: To regenerate and detect api drift look at `api/README.md`
- **Web**: run from `web/` — `pnpm install`, `pnpm dev` (SPA on `http://localhost:5173`, `/api` proxied to
  `http://localhost:8080`), `pnpm check` (generate:check, lint, format:check, typecheck, test, build; ~20 s). After
  a server DTO/controller change regenerate the spec (`api/README.md`) and then `pnpm generate` in `web/`.
  Image: `docker build -f apps/web/Dockerfile -t sift-web:dev web/`.

# Definition of Done

- `./kotlin check` (detekt + unit tests) passes
- `pnpm check` in `web/` passes when anything under `web/` or `api/` changed
- After implementing a new feature or changing behaviour of operator, agents, server, messaging/events, CRDs or k8s
  manifests, run the e2e suite via `tools/e2e.sh` and make sure it is green before considering the work done. Pure
  documentation, comment or formatting changes do not require an e2e run.
- Documentation in `docs/` updated as described in Context Pointers

# Context Pointers

- when deciding on system architecture update `docs/system-architecture.md`
- ADR should document every decision in `docs/adrs`
- when creating new system components, create documentation in `docs/system-components`
- when updating system components, update documentation in `docs/system-components`