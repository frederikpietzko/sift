# Project Intent

Open Source Agentic Code Review Platform designed for self hosting and deployment on k8s.

# Tooling

Kotlin toolchain (formerly Amper), use `./kotlin --help` to explore usage.

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
- server: Kotlin, Spring Boot 4, fabric8-client; applies CRs to cluster, exposes API to FE
- e2e: Kotlin, JUnit 5, Testcontainers/kind; gated end-to-end scenario (`SIFT_E2E=true`) that spins up a kind cluster,
  deploys operator + agents and runs a real code review; see `docs/system-components/e2e-tests.md`
- tools: helper scripts for local development, eg `tools/e2e.sh` (secrets live in the git-ignored `tools/e2e.env`)
- UI out of scope atm

# Tooling & Execution

- **Build System**: Use `./kotlin` for build and run tasks
- **Linting**: Use `./kotlin check detekt`
- **Tests**: Use `./kotlin test` or `./kotlin check test`
- **Checks**: Use `./kotlin check` to run all checks
- **E2E Tests**: Use `tools/e2e.sh` (sources credentials from `tools/e2e.env` automatically, sets `SIFT_E2E=true`, runs
  `./kotlin check tests --module e2e`). Flags: `--destroy` (delete kind cluster afterwards),
  `--pr owner/repo#N`, `--timeout 45m`, `-- <extra args>` forwarded to `./kotlin check`. Takes ~1 min. If
  `tools/e2e.env` is missing, copy `tools/e2e.env.example` and ask the user for the tokens; never commit it.
- **k8s**: k8s kind cluster running, kubeconfig at `./.kubeconfig`

# Definition of Done

- `./kotlin check` (detekt + unit tests) passes
- After implementing a new feature or changing behaviour of operator, agents, server, messaging/events, CRDs or k8s
  manifests, run the e2e suite via `tools/e2e.sh` and make sure it is green before considering the work done. Pure
  documentation, comment or formatting changes do not require an e2e run.
- Documentation in `docs/` updated as described in Context Pointers

# Context Pointers

- when deciding on system architecture update `docs/system-architecture.md`
- ADR should document every decision in `docs/adrs`
- when creating new system components, create documentation in `docs/system-components`
- when updating system components, update documentation in `docs/system-components`