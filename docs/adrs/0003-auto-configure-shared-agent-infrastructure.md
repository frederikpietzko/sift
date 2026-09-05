# ADR 0003: Auto-configure Shared Agent Infrastructure

Date: 2026-09-06

## Status

Accepted

## Context

Agent applications scan their own packages. The sibling `org.sift.agents.shared` package is
not discovered by that scan, leaving required beans such as `EventPublisher` unavailable.
Tests that import shared configuration classes directly do not detect this startup failure.

## Decision

Register `MessagingConfiguration` and `WebSearchConfiguration` as Spring Boot
`@AutoConfiguration` classes in the shared module's
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` resource.
Applications depending on `agents/shared` need neither broader component scans nor manual imports.

- Messaging configuration runs before `RabbitAutoConfiguration`, making the JSON message
  converter available to Boot's `RabbitTemplate` configuration.
- Web-search configuration runs after `RestClientAutoConfiguration` and uses Boot's
  `RestClient.Builder`. Search remains enabled by default with the existing explicit opt-out.
- Default beans back off for application-provided `EventPublisher`, AMQP `MessageConverter`,
  and `SearxngSearchTool` beans. The exchange backs off by the name `siftEventsExchange`, so an
  unrelated exchange does not suppress its registration.
- Connection settings remain in consuming applications, including their local profiles.

## Alternatives

- Broad component scanning couples applications to shared package structure and risks pulling
  in unrelated components.
- Explicit imports require each agent to repeat the same infrastructure wiring.

## Consequences

Adding the shared module activates its infrastructure defaults automatically. Consumers can
replace individual defaults or exclude a configuration using Spring Boot's standard
`spring.autoconfigure.exclude` property with its fully qualified class name.

Discovery tests use `@EnableAutoConfiguration` without component scanning or explicit shared
imports, and verify default wiring, search opt-out, and application bean overrides without
contacting external services.