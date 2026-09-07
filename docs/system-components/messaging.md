# Messaging

`messaging` is a small Spring Boot library (`org.sift.messaging`) that owns the RabbitMQ publishing
side shared by all Sift producers. It depends on `events` (exported) and Spring AMQP only, so
components such as the operator can publish without pulling in Spring AI. See
[ADR 0001](../adrs/0001-use-rabbitmq-as-message-queue.md) and
[ADR 0011](../adrs/0011-operator-status-events-via-rabbitmq.md).

## Contents

- `EventPublisher` — `fun publish(event: SiftEvent)`; the only abstraction application code uses.
- `RabbitEventPublisher` — publishes to the durable topic exchange `sift.events`
  (`RabbitEventPublisher.EXCHANGE`) under `event.routingKey`. No retry logic of its own.
- `MessagingConfiguration` — `@AutoConfiguration(before = RabbitAutoConfiguration)` registered in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Provides the
  `siftEventsExchange` bean, a Jackson 3 `JacksonJsonMessageConverter` with the Kotlin module, and
  the default `EventPublisher`. Every bean backs off for an application-provided one (the exchange by
  bean name `siftEventsExchange`).

## Consumers

| Module | Purpose |
|---|---|
| `agents/shared` | Re-exports `messaging`; agents publish `CodeReviewCompletedEvent` (`code-review.completed`). |
| `k8s/operator` | `ReviewStatusPublisher` publishes `CodeReviewStatusChangedEvent` (`code-review.status`) after each persisted status write. |
| `server` | Declares its queues on `sift.events` and consumes both routing keys (see the server component documentation). |

## Configuration

Connection settings are deliberately not part of this module. Applications set
`spring.rabbitmq.host/port/username/password/virtual-host`, `spring.rabbitmq.connection-timeout`
and `spring.rabbitmq.template.retry.*` in their own `application.yaml`, usually from
`SPRING_RABBITMQ_*` environment variables. Tests that load a context without a broker set
`spring.rabbitmq.dynamic=false`.
