# Code Review Agent

The code review agent (`agents/code-review`, package `org.sift.agents.review`) is a Spring Boot
batch-style application that reviews a single change set and exits. It is designed to run as a
Kubernetes job scheduled by the Sift Operator.

## Purpose

Given a repository, a branch under review, and a base branch, the agent checks out the code,
lets an LLM review the diff (with tools to explore the surrounding code), and publishes the
result as a `code-review.completed` event to the `sift.events` topic exchange on RabbitMQ
(see [ADR 0001](../adrs/0001-use-rabbitmq-as-message-queue.md)).

## Pipeline

The `ReviewRunner` (`ApplicationRunner`) orchestrates a single run:

1. **Checkout** — `GitCheckoutService.checkout(properties)` clones the repository into a
   temporary directory, fetches the base branch and the branch under review, and computes the
   diff (`baseBranch...branch`). Fails with `GitCommandException` on any git error or timeout.
2. **Review** — `ReviewAgent.review(checkout)` sends the (capped) diff to the LLM together with
   shell, grep, glob, and file-system tools scoped to the checkout directory, plus the optional
   SearXNG web-search tool (enabled by default). The call is retried once on failure and returns a
   structured `ReviewResult` (summary + findings).
3. **Publish** — the result is mapped to a `CodeReviewCompletedEvent` and published via the
   shared `EventPublisher` under the routing key `code-review.completed` to the `sift.events`
   exchange.
4. **Cleanup** — the checkout directory is deleted in a `finally` block, regardless of outcome.

If publishing fails, the full event payload is logged as JSON at ERROR level (so the review
result is not lost) and the exception is rethrown.

## Configuration Reference

The `agents/shared` dependency automatically registers messaging and web-search infrastructure
through Spring Boot auto-configuration; no shared-package component scan or explicit import is
needed. Applications can provide their own `EventPublisher`, AMQP `MessageConverter`, or
`SearxngSearchTool` bean to replace a default, or a bean named `siftEventsExchange` to replace the
exchange. Connection settings still come from application configuration and profiles
(see [ADR 0003](../adrs/0003-auto-configure-shared-agent-infrastructure.md)).

### `sift.review.*` (required)

| Property | Description |
|---|---|
| `sift.review.repository-url` | URL of the repository to review |
| `sift.review.branch` | Branch under review |
| `sift.review.base-branch` | Base branch to diff against |
| `sift.review.pull-request` | Optional pull request identifier, propagated to the event |
| `sift.review.auth-token` | Optional token injected into the clone URL (HTTPS only) |

### `sift.tools.web-search.*` (optional, defaults shown)

| Property | Default | Description |
|---|---|---|
| `sift.tools.web-search.enabled` | `true` | Set to `false` to disable the SearXNG web-search tool (see [ADR 0002](../adrs/0002-airgapped-agents-with-optional-searxng-web-search.md)) |
| `sift.tools.web-search.base-url` | `http://localhost:8888` | Base URL of the SearXNG instance |
| `sift.tools.web-search.max-results` | `5` | Maximum number of search results returned to the model |

### RabbitMQ

The agent uses standard `spring.rabbitmq.*` properties. The defaults in `application.yaml` keep a
broker outage from hanging the job:

- `spring.rabbitmq.connection-timeout: 5s` — connection attempts fail fast.
- `spring.rabbitmq.template.retry` — bounded publish retries (3 attempts, 1s initial interval,
  multiplier 2.0).

There are no listener containers; the connection is only opened lazily when the result is
published at the end of the run.

### LLM

`spring.ai.openai.*` (base URL, API key, chat model) configures the OpenAI-compatible endpoint.

Reasoning is temporarily disabled with `spring.ai.openai.chat.reasoning-effort: none` because
the configured endpoint rejects reasoning with function tools over Chat Completions.

### Logging

The runner logs checkout, AI review, the summary and finding count, publication, and cleanup
at INFO level. `org.springframework.ai` is configured at DEBUG, and the review client includes
`SimpleLoggerAdvisor` to log its request and response. The advisor logs the outer client call;
it does not provide a heartbeat while waiting for a model response or guarantee a log for every
internal tool-call round.

DEBUG logs can contain prompts, repository diffs, tool context, and model output, including
sensitive source content. Restrict access and retention. Set
`logging.level.org.springframework.ai=INFO` to disable these diagnostic logs while retaining
runner progress messages. HTTP wire logging is not enabled.

## Local Development

1. Start the local infrastructure (RabbitMQ on 5672 with `sift`/`sift`, SearXNG on 8888):

   ```shell
   docker compose up -d
   ```

2. Run the agent with the `local` profile, which wires RabbitMQ and provides sample
   `sift.review.*` values. Web search is enabled by default against local SearXNG:

   ```shell
   ./kotlin run --module code-review -- --spring.profiles.active=local
   ```

## Exit-Code Semantics

The agent runs once and exits. After `runApplication` returns (all application runners have
completed), `main` explicitly closes the Spring context, releasing RabbitMQ connections and
other managed resources rather than relying on the JVM shutdown hook. Non-web mode alone
does not close the context (see [ADR 0004](../adrs/0004-close-one-shot-agent-context.md)).

- **0** — checkout, review, and publish all succeeded.
- **non-zero (1)** — any step failed. An exception thrown from the `ApplicationRunner` aborts
  startup; Spring Boot closes the failed context, `runApplication` rethrows the exception, and
  the JVM exits with code 1. Kubernetes marks the job
  as failed and can retry it according to its backoff policy.

## Airgap Posture

The web-search tool is enabled by default and sends queries to the configured SearXNG instance
(`http://localhost:8888` by default). Configure `sift.tools.web-search.base-url` for deployments
where SearXNG runs elsewhere, or set `sift.tools.web-search.enabled=false` to remove the tool.
Search request failures return an explanatory result so the model can proceed without search.

Disabling search does not enforce network isolation: shell commands and other dependencies may
still access the network. Airgapped deployments must enforce egress restrictions and provide
reachable LLM, git, and RabbitMQ services
(see [ADR 0002](../adrs/0002-airgapped-agents-with-optional-searxng-web-search.md)).
