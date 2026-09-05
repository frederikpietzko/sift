# ADR 0002: Default-Enabled SearXNG Web Search with Explicit Opt-Out

Date: 2026-09-06

## Status

Accepted

## Context

Agent jobs (code review, security review) run untrusted-adjacent workloads: they check out
arbitrary repository contents and hand an LLM tools such as shell, grep, glob, and file-system
access inside the checkout. Sift is self-hosted, and many target environments (enterprises,
regulated industries) restrict or forbid outbound internet access from cluster workloads.

At the same time, web search genuinely improves review quality: agents can look up library
documentation, CVEs, and error messages instead of hallucinating them.

Constraints and priorities:

- Agents must support restricted deployments; operators must be able to disable web search.
- When web access is desired, operators need a controlled, auditable egress point rather than
  letting agent pods reach the internet directly.
- The whole backend stack is Kotlin + Spring Boot 4, so simple conditional wiring matters.

## Decision

Web search is **enabled by default**, backed by a **self-hosted SearXNG instance**, with an
explicit opt-out for deployments that do not want search.

- The web-search tool bean is created when `sift.tools.web-search.enabled` is absent or `true`
  (`@ConditionalOnProperty` with `matchIfMissing = true` in `agents/shared`'s
  `WebSearchConfiguration`). Set the property to `false` to remove it from the model's tools.
- When enabled, the `SearxngSearchTool` talks exclusively to the SearXNG instance configured via
  `sift.tools.web-search.base-url`. SearXNG is a self-hostable metasearch engine, so the
  operator controls the single egress point (and can proxy, rate-limit, or log it).
- The default endpoint is `http://localhost:8888`, where SearXNG runs via
  [docker-compose](../../compose.yaml) for local development. Other deployments must configure
  the endpoint to reach their own instance.
- The search toggle is not a network-isolation boundary. Airgapped deployments must enforce
  egress restrictions independently, including for shell tools, and provide reachable LLM,
  git, and RabbitMQ services.

Rationale:

- **Useful default**: the model can look up documentation and security information without a
  separate enablement flag; operators retain an explicit opt-out.
- **Single controlled egress**: routing all searches through SearXNG means network policies only
  need to allow agent → SearXNG, and SearXNG's own engine configuration limits what is queried.
- **No vendor lock-in / no API keys**: SearXNG aggregates upstream engines without per-agent API
  keys or per-request billing, fitting the self-hosted, open-source posture of the platform.

## Alternatives Considered

- **Direct search-engine APIs (Google Programmable Search, Bing, Brave, Tavily)** — simpler
  client code, but requires API keys and billing per deployment, sends queries to a third party,
  and contradicts the self-hosted posture; unusable in airgapped environments anyway.
- **Opt-in web search** — requires extra configuration to expose a useful tool; rejected in
  favor of default availability with an explicit opt-out.
- **Mandatory web search without an opt-out** — unsuitable for restricted deployments.
- **Full egress with network-policy allowlists only** — pushes all responsibility to cluster
  operators and provides no application-level control or audit point.

## Consequences

- Deployments using search must operate a reachable SearXNG instance and configure `base-url`
  when it differs from the local default. Kubernetes manifests for SearXNG remain future work.
- Search request failures return an explanatory tool result so the model can proceed without
  search results. Deployments without SearXNG should explicitly disable the tool.
- The tool surface exposed to the model differs between deployments (with/without search), which
  should be kept in mind when comparing review results across environments.
- Search queries may contain sensitive data or be influenced by prompt injection. SearXNG may
  forward queries to upstream engines; operators must configure engines and network controls
  appropriately. The search tool's fixed endpoint does not restrict shell-tool network access.
