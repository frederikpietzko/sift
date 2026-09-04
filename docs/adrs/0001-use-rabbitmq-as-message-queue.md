# ADR 0001: Use RabbitMQ as the Message Queue

Date: 2026-09-05

## Status

Accepted

## Context

The system architecture ([system-architecture.md](../system-architecture.md)) requires a message queue to transport:

- Agent results and status updates (reviewer / security scanner jobs → Sift Server)
- VCS events (PR created, comment on PR thread) from the VCS Adapter → Sift Server

Constraints and priorities:

- The platform is self-hosted and deployed on Kubernetes; every additional infrastructure component raises the
  adoption barrier, so operational footprint matters.
- The whole backend stack is Kotlin + Spring Boot 4, so first-class Spring integration is a priority.
- Expected message volume is low to moderate (a handful of messages per review), so raw throughput is not a
  deciding factor.

## Decision

We use **RabbitMQ** as the message queue, integrated via **Spring AMQP** (`spring-boot-starter-amqp`).

Rationale:

- **First-class Spring support**: Spring AMQP is an official Spring project maintained in lockstep with Spring Boot
  releases (including Boot 4). We get `@RabbitListener`, `RabbitTemplate`, converter-based payload mapping,
  declarative retries and dead-letter queues, and a solid `@SpringBootTest` + Testcontainers story out of the box.
- **Fitting semantics**: work queues and fan-out exchanges map directly onto our flows
  (`agent results → server`, `VCS events → server`, later `server → notification consumers`).
- **Acceptable ops on k8s**: the official RabbitMQ Cluster Operator or a Helm chart make single-node or 3-node
  clusters straightforward at our scale.

For local development, RabbitMQ (and Postgres) run via [docker-compose](../../compose.yaml).

## Alternatives Considered

- **NATS + JetStream** — the lightest self-hostable MQ operationally (single small binary), but no official Spring
  project; the community binder lags Spring Boot major releases, which is a real risk on Boot 4.
- **Postgres-backed queue** (pgmq / `SELECT ... FOR UPDATE SKIP LOCKED`) — zero new infrastructure and transactional
  consistency for free, but couples agents and the VCS Adapter to the database (wider blast radius) and would need
  replacing once real decoupling/fan-out is required.
- **Kafka / Redpanda** — first-class Spring support, but clear overkill for our volume; heavy operationally and we
  do not need log semantics or replay at scale.
- **Redis Streams** — only attractive if Redis were already in the stack; it is not, and delivery guarantees are
  weaker.

## Consequences

- Producers (agents, VCS Adapter) and the consumer (Sift Server) integrate via Spring AMQP.
- Messaging should be hidden behind a small publisher/consumer abstraction (e.g. in `agents/shared` and `server`) to
  keep broker specifics contained.
- Deployment manifests / Helm chart for RabbitMQ need to be added under `k8s/manifests` for cluster deployments.
- One more stateful component to operate compared to a Postgres-backed queue; mitigated by the RabbitMQ Cluster
  Operator and modest scale.
