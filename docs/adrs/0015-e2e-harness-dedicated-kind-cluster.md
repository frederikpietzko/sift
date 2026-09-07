# ADR 0015: Real end-to-end harness on a dedicated kind cluster

Date: 2026-09-07

## Status

Accepted. Amends [ADR 0009](0009-local-kind-connectivity.md) for the `sift-e2e` cluster only.

## Context

Until now the only proof that server → operator → agent → events → server works together was a
manual gate (`k8s/local/acceptance.py`, [evidence](../validation/code-review-e2e-2026-09-06.md))
plus module tests that mock the cluster (`ServerEndToEndTest`) or publish the events themselves.
Integration regressions between the components were therefore only caught by hand.

[ADR 0009](0009-local-kind-connectivity.md) deliberately forbids the local helpers to create or
replace clusters, replace the kubeconfig or install the CRD implicitly, because they operate on
the administrator-owned `kind-kind` cluster through the root `.kubeconfig`. An automated test
that must bootstrap itself on any developer machine cannot honour those rules on that cluster
without either mutating shared state or requiring a multi-page manual setup.

## Decision

- Add a top-level `e2e` module (JUnit Jupiter 6 + Awaitility, no new framework) with one
  happy-path scenario that drives **only the server's public API** and asserts only on stable
  contracts: HTTP status codes, phase transitions observed via SSE, presence/shape of the run and
  its result. LLM output, timings and intermediate resource names are never asserted.
- The harness runs the platform **for real**: nothing is mocked. Postgres and RabbitMQ come from
  `docker compose up -d --wait postgres rabbitmq`; the operator and the server run as separate
  host JVMs started with `./kotlin run --module …` under a scrubbed environment, exactly as
  developers run them; review Jobs use the published agent image and the HAProxy bridges.
- The harness uses a **dedicated kind cluster `sift-e2e`** with its own kubeconfig at
  `build/e2e/kubeconfig`. It creates the cluster when absent, reuses it otherwise, and installs the
  CRD, `k8s/manifests/local/*.yaml` and `k8s/manifests/operator/*.yaml` idempotently via
  server-side apply (field manager `sift-e2e`). The `sift-local-credentials` Secret is built from
  the process environment through the fabric8 API body only. `SIFT_E2E_DESTROY=true` deletes the
  cluster; by default cluster, Compose containers and credentials are kept for fast re-runs.
- **Exception to ADR 0009:** creating a cluster, owning a kubeconfig and installing the CRD
  automatically is permitted **only** for the `sift-e2e` cluster and `build/e2e/kubeconfig`. The
  root `.kubeconfig`, the `kind-kind` cluster and the `dev.py`/`acceptance.py` helpers are
  untouched and keep the ADR 0009 rules.
- The bootstrap is written in Kotlin inside the module (thin `ProcessBuilder` wrappers around
  `kind`, `docker compose` and `./kotlin run`, fabric8 for in-cluster resources) rather than
  reusing the Python helpers, so the harness is unit-testable, detekt-checked and not coupled to
  the `kind-kind` guards of `dev.py`.
- The module does not depend on `//server` or `//k8s/operator` (no classpath or auto-configuration
  bleed); it depends on `//k8s/crds` for the typed `CodeReview` used in cleanup.
- The scenario is gated behind `SIFT_E2E=true` and skipped otherwise, so `./kotlin check` stays
  green without Docker, kind or credentials. Preflight fails fast with actionable messages before
  any side effect.
- Cleanup removes test data only (CR and `sift-repo-*` Secret via fabric8, `review_results`,
  `agent_runs`, `repositories` rows via JDBC). The API delete is not used because
  `DELETE /api/v1/repositories/{id}` fails on the foreign key once a run has reached a terminal
  phase; that is a recorded server follow-up, not something the harness works around silently.

## Alternatives

- Reuse the developer's `kind-kind` cluster through the root `.kubeconfig`: would either violate
  ADR 0009 or require manual setup before every run, and would let the test mutate shared state.
- Deploy operator and server *into* the cluster: closer to production but slower (image builds
  per run) and different from how developers run them; the host-JVM setup is what the request
  asked to verify.
- Stop at a scheduling-only check with an unpullable image (as `scheduling_check.py` does): does
  not prove the agent image, the bridges or the event contracts.
- Assert on review content: brittle against model and prompt changes; the goal is a test that
  rarely needs edits.
- Drive the harness from the Python helpers: keeps two languages and inherits guards that
  intentionally refuse to create clusters.

## Consequences

- One command (`SIFT_E2E=true ./kotlin check tests --module e2e`) proves the whole platform on a
  developer machine; see [end-to-end tests](../system-components/e2e-tests.md) for prerequisites,
  variables, teardown and troubleshooting.
- Two kind clusters may coexist on one host; both rely on Docker Desktop's `host.docker.internal`
  and only use ClusterIP Services, so there are no port conflicts. The node architecture must match
  the published image.
- Runs are slow on a fresh machine (cluster creation, image pull, JVM compile) and consume model
  budget; budgets are generous and configurable (`SIFT_E2E_TIMEOUT`). Concurrent runs against the
  same namespace/database are unsupported. CI wiring, negative scenarios and in-cluster
  deployment of operator/server remain out of scope.
