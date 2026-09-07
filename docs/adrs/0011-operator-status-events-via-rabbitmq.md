# ADR 0011: Operator publishes CodeReview status events via RabbitMQ

Date: 2026-09-07

## Status

Accepted

## Context

The Sift Server needs a read model of running agents (phase, timestamps, execution identity)
to serve list/get requests and a live watch stream without every client polling the Kubernetes
API. Three placements for the CR observer were considered:

- an informer inside the server, duplicating the watch the operator already runs and coupling
  the server to cluster credentials for reads;
- the operator writing into the server's Postgres, creating a shared database between two
  components with different lifecycles;
- the operator publishing status changes as events and the server projecting them into its own
  tables.

The publisher used by agents (`EventPublisher`, `RabbitEventPublisher`, `MessagingConfiguration`)
lived in `agents/shared`, which also pulls in Spring AI. The operator must not depend on Spring AI.

## Decision

- Extract the publisher and its auto-configuration into a new `messaging` module
  (`org.sift.messaging`) that depends only on `events` and Spring AMQP. `agents/shared`,
  `k8s/operator` and the future `server` depend on it; `agents/shared` re-exports it so agents
  are unaffected.
- Add `CodeReviewStatusChangedEvent` (routing key `code-review.status`) to `events`. It carries
  the CR identity (`reviewName`, `reviewNamespace`, `reviewUid`, `generation`, `executionId`), a
  spec snapshot, the persisted `phase`/`reason`/`message`/timestamps and an `observedAt` instant.
  `phase` is a plain string so `events` stays free of `k8s/crds`.
- `CodeReviewReconciler.save()` remains the single place that writes status. Immediately after a
  successful `updateStatus()` it calls `ReviewStatusPublisher`, which publishes the event.
  Publishing is best effort: `AmqpException`s are logged and never fail or retry the
  reconciliation, because the Kubernetes status is the source of truth.
- Consumers must treat delivery as at-least-once and possibly out of order: they compare
  `generation` and `observedAt` with the stored state and never let a late event overwrite a
  terminal state.
- The operator reads `spring.rabbitmq.*` from `SPRING_RABBITMQ_HOST/PORT/USERNAME/PASSWORD/VIRTUAL_HOST`
  (defaults target the local `compose.yaml` broker) with the same template retry policy as the
  agents. The local `dev.py run` helper now keeps `SPRING_RABBITMQ_PASSWORD` in the operator's
  environment.

## Alternatives

- Informer in the server: duplicates watching, requires cluster read RBAC in the server and
  does not scale to non-Kubernetes agent runtimes.
- Shared Postgres between operator and server: hidden coupling through schema, two writers to
  one table, and the operator gains a database dependency.
- Transactional outbox in the operator: the operator has no database; the CR status itself is
  the durable record, and a missed event can be repaired by re-reading the CR later.

## Consequences

- A broker outage during a status write loses that event; the CR is still correct. A periodic
  reconciliation endpoint on the server (re-reading CRs) is a documented follow-up.
- The status event stream is the only Kubernetes-derived input to the server's `agent_runs`
  read model; the server never watches CRs.
- `agents/shared` no longer registers `MessagingConfiguration`; the `messaging` module does.
  ADR 0003 continues to apply to the remaining shared beans.
