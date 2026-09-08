# ADR 0014: Defer Sift Server API authentication

Date: 2026-09-07

## Status

Superseded by [ADR 0016](0016-oauth2-resource-server-and-user-attribution.md) (OAuth2 resource
server with JWT bearer tokens and user attribution). Kept for the reasoning behind the interim
anonymous API.

## Context

The Sift Server exposes `/api/v1/repositories`, `/api/v1/agents` (including the SSE watch) and
`/api/v1/results` plus the actuator health endpoints. The repositories API accepts VCS access
tokens, which the server encrypts at rest and mirrors into namespace Secrets
([ADR 0012](0012-server-managed-repository-credentials.md)); the agents API creates `CodeReview`
CRs that start Jobs in the cluster. Both are privileged operations.

There is no client yet that could carry credentials (the UI is out of scope for this iteration), no
identity provider has been chosen, and the deployment target is a self-hosted cluster whose ingress
and network policies are controlled by the same administrator who runs the server. Adding
Spring Security now would force a decision on the token format and identity source before any
consumer exists and would slow down the API iteration the UI work depends on.

## Decision

- The server ships **without** authentication or authorisation in this iteration. Spring Security
  is not on the classpath; every endpoint is anonymous.
- The deployment relies on the platform boundary instead: the `sift-server` Service is
  `ClusterIP`-only ([`k8s/manifests/server/service.yaml`](../../k8s/manifests/server/service.yaml)),
  no Ingress is shipped, and operators are expected to restrict access with an authenticating
  ingress/proxy or NetworkPolicy until the follow-up lands. The server must never be exposed
  directly to an untrusted network.
- The intended follow-up is Spring Security 7 with one of:
  - a static bearer token (`sift.server.auth.token`, from a Secret) for machine clients and the
    first UI, or
  - an OIDC resource server (`spring-boot-starter-oauth2-resource-server`) validating JWTs from
    the administrator's identity provider, with roles mapped to read (`results`, `agents` GET/watch)
    and write (`repositories`, `agents` POST/cancel) scopes.
  The actuator liveness/readiness probes stay unauthenticated in both variants.
- The API must not grow features that assume anonymity (for example, tenant-less global state that
  cannot later be scoped to a caller); DTOs and paths are expected to survive the introduction of
  authentication unchanged.

## Alternatives

- Static bearer token now: cheap, but leaks into every client, script and doc from the start and
  still requires the ingress boundary for TLS; deferred rather than rejected.
- OIDC resource server now: requires an identity provider decision and client-side login flow the
  absent UI cannot exercise; premature.
- Kubernetes-native authentication (TokenReview/SubjectAccessReview per request): couples API
  clients to cluster identities and does not fit the external UI/IDE-plugin clients.

## Consequences

- Anyone who can reach the Service can register repositories with tokens, start reviews (consuming
  model budget and cluster resources), cancel runs and read all findings. Network reachability is
  the only control; misconfigured ingress equals full compromise of the stored tokens' capabilities
  (not their plaintext, which is never returned).
- Audit trails are impossible: `agent_runs.source = API` records that a call happened, not who made
  it. Adding a caller identity column later is a schema migration, not a redesign.
- Rate limiting and per-user quotas are out of scope with authentication.
- When the follow-up lands, `server.md` and `k8s/manifests/server/*` must gain the corresponding
  configuration keys and Secret entries, and this ADR is to be superseded.
