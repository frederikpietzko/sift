# ADR 0013: Agent run watch via Postgres NOTIFY and Server-Sent Events

Date: 2026-09-07

## Status

Accepted

## Context

The UI needs to follow agent runs live: a list that updates as the operator reports phases, and a
detail view that follows one run to completion. The server already owns the `agent_runs` read model
(ADR 0011); every write to it — API create/cancel as well as `code-review.status` events — goes through
the Flyway-managed trigger `agent_runs_notify`, which emits `pg_notify('sift_agent_runs',
{id, phase, updatedAt})`. The server is a Spring MVC (servlet) application on Kotlin coroutines, is
meant to run with several replicas on Kubernetes, and clients may be behind proxies that drop idle
connections.

## Decision

- **Push, not poll.** `GET /api/v1/agents/watch` is a Server-Sent Events stream (`text/event-stream`).
  Spring MVC adapts the controller's Kotlin `Flow<ServerSentEvent<AgentRunEvent>>` to an `SseEmitter`
  (`kotlinx-coroutines-reactor`); `spring.mvc.async.request-timeout=-1` keeps streams open.
- **Postgres is the fan-out bus.** Each server replica runs exactly one listener coroutine
  (`PgNotificationListener`, launched in the injected `ApplicationCoroutineScope` on
  `ApplicationReadyEvent`). It holds a dedicated non-pooled JDBC connection
  (`application_name = sift-server-watch`) with `LISTEN sift_agent_runs`, polls
  `PgConnection.getNotifications(1000)` on `Dispatchers.IO`, reloads the run by id through the pool and
  publishes an `UPDATED` event into `AgentRunEvents`, an in-process `SharedFlow` (no replay, buffer 256,
  `DROP_OLDEST`). Connection loss is retried with exponential backoff (500 ms → 30 s).
- **Snapshot on connect, resume by `Last-Event-ID`.** `AgentRunWatchService` subscribes to the shared
  flow first and then emits a `SNAPSHOT` for the matching runs (`agentId` → that run; otherwise the
  newest 200, or — when the client sends `Last-Event-ID` — only runs with `updated_at` after that
  epoch-millisecond). Live events are filtered by `agentId`/`kind`. Each data frame has
  `id = updatedAt epoch millis` and `event = SNAPSHOT | UPDATED`.
- **Heartbeat.** A comment frame (`:heartbeat`) is merged in every `sift.server.watch.heartbeat`
  (default 15 s) so idle streams survive proxies and dead clients are detected by the container.
- `sift.server.watch.enabled=false` turns the listener off (tests without a live watch, tooling); the
  endpoint then still serves snapshots.

## Alternatives

- **Client polling of `GET /api/v1/agents`.** Simple, but per-client load grows with the number of
  open views and latency is bounded by the poll interval; the trigger already exists, so push is cheap.
- **RabbitMQ fan-out per replica** (exclusive queue per server instance bound to `code-review.status`).
  Only covers operator events, not API writes (create/cancel), and duplicates the projection logic in
  every replica; Postgres notifications fire on the committed row, i.e. after the projection.
- **Kubernetes informer in the server.** Rejected in ADR 0011 already: cluster read RBAC in the server,
  duplicated watching, and no coverage for non-Kubernetes runs.
- **Reactive stack (WebFlux + R2DBC).** Would remove the poll loop, but the server is a servlet
  application with Exposed/JDBC throughout; one blocking `LISTEN` connection per replica is negligible.

## Consequences

- Multi-replica safe: every replica gets every notification from Postgres; a client is served by the
  replica it is connected to and sees the same stream regardless of which replica wrote the row.
- Live events are at-most-once: `NOTIFY` is not durable, the shared flow drops the oldest event for a
  slow subscriber, and nothing is replayed after a listener reconnect. This is acceptable because every
  event carries the complete run, the subscription is established before the snapshot is loaded (a
  write in between is delivered, possibly twice), and reconnecting clients catch up via
  `Last-Event-ID` → `SNAPSHOT` of runs updated since then. Clients must treat events as
  "state is now X", not as deltas.
- An empty snapshot produces no frame; clients should treat the stream as "up to date" once the
  response headers arrive, and rely on heartbeats for liveness.
- The listener needs one extra Postgres connection per replica outside the Hikari pool; connection
  limits must account for it.
- `/api/v1/agents/{id}` restricts `{id}` to a UUID pattern so `watch` can never be mistaken for a run
  id even when a client omits the `text/event-stream` accept header (such a request gets `406`).
