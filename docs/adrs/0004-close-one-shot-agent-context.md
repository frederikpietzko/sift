# ADR 0004: Close the One-Shot Agent Context

Date: 2026-09-06

## Status

Accepted

## Context

The code-review agent is a one-shot command intended for a Kubernetes job. Completing an
`ApplicationRunner` does not close Spring's application context, even in non-web mode.
RabbitMQ connections and other managed resources can therefore keep the JVM alive after
the review and publication have finished.

## Decision

Close the code-review application's context in `main` immediately after `runApplication`
returns. Spring Boot runs the application runners synchronously before returning, so this
preserves review, publication, and checkout cleanup before resource destruction.

Let startup and runner exceptions propagate. Spring Boot closes the context on startup
failure; an uncaught exception preserves the nonzero process exit status.

## Consequences

Successful jobs release managed resources without waiting for a termination signal.
No forced process exit or daemon-thread workaround is needed for RabbitMQ. This does not
bound model or tool execution time: shutdown occurs only after runners finish.

Lifecycle regression tests verify context closure, resource destruction, and exception
propagation. The server remains long-lived and does not adopt this entry-point behavior.