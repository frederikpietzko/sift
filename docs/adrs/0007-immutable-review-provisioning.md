# ADR 0007: Immutable dependent review provisioning

## Status

Accepted.

## Context

A manually applied CodeReview generation must produce an owned configuration snapshot
before its Job without letting operator configuration changes mutate scheduled work.
The Spring starter is pinned to 6.6.0 (SDK 5.5.0), so dependency wiring must use APIs
available there. Cancellation and terminal-state handling must run before provisioning.

## Decision

- Constructor-inject Spring-dependent instances and build an SDK workflow with explicit
  ConfigMap-before-Job dependency ordering. Register their informer event sources from
  the reconciler; invoke the workflow under reconciler control, not automatically.
- Implement only SDK `Creator` and `GarbageCollected`. Do not implement `Updater` or
  compare mutable operator defaults against established execution snapshots. Use POST
  creation and direct API identity/ownership reads to prevent adoption and overwrite
  during informer delay or creation conflicts.
- Use a bounded DNS prefix, 64-bit SHA-256-derived UID suffix and generation for names;
  retain full UID/generation labels and controller owner references. Hash collisions
  cannot cause adoption because ownership is checked separately.
- Serialize an explicit set of properties to immutable `application.yaml`; references
  to Secrets become named environment entries, never fetched values. A trusted model
  URL `{proxyToken}` slot becomes an environment placeholder, preserving the proxy path.
  Reject embedded repository credentials and CR-provided Spring placeholders.
- Mount mandatory `SPRING_CONFIG_ADDITIONAL_LOCATION` read-only, preserving default
  Spring search locations. Never set both configuration-location variables. Use
  nonroot restricted Jobs with scratch-only writable storage and no whole-Job retries.

## Consequences and verification

The kind API test verifies ordered creation, immutable-template rejection, restart/image
snapshot preservation and owner-reference garbage collection. New operator defaults
affect future executions only. [ADR 0008](0008-generation-safe-review-lifecycle.md)
extends the workflow with cancellation, generation replacement and durable status.

Spring Boot enables `settings.jvm.runtimeClasspathMode: classes` by default for DevTools
restarts. That mode packages local module outputs as `BOOT-INF/lib` directories the Boot
loader cannot load (KTC-5686). `agents/code-review` sets `runtimeClasspathMode: jars` so
`./kotlin package --module code-review --format executable-jar` nests `events-jvm.jar` and
`shared-jvm.jar` and starts under `java -jar`.

Packaged startup validation proves mandatory `SPRING_CONFIG_ADDITIONAL_LOCATION` binds
mounted review values over packaged defaults, retains defaults from
`agents/code-review/resources/application.yaml`, and fails when the mounted file is missing.
No `SPRING_CONFIG_LOCATION` fallback is used. Validation stages sources without
`application-local.yaml`; normal unstaged packaging can still include it under
`BOOT-INF/classes` and the nested module jar—Step 5 must keep excluding it before publish.
See the [operator component](../system-components/code-review-operator.md) for commands
and evidence. The agent's SHA/identity contract remains an independent external blocker.

The advisor boundary is unchanged; see [ADR 0005](0005-enforce-agent-tool-allowlists.md)
and its [component documentation](../system-components/tool-allowlist-advisor.md).
