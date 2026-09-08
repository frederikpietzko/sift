# ADR 0016: OAuth2 resource server and user attribution for the Sift Server API

Date: 2026-09-07

## Status

Accepted. Supersedes [ADR 0014](0014-defer-server-api-authentication.md).

## Context

[ADR 0014](0014-defer-server-api-authentication.md) shipped the server without authentication and
relied on the ingress/network boundary, with the explicit promise that paths and DTOs would survive
the introduction of authentication and that the follow-up would supersede it. The web client is now
being planned, so the server needs a login story before any UI code exists, and the platform needs
to know **who** requested a run: `agent_runs.source = API` recorded that a call happened, not by
whom, which made audit trails and "my runs" views impossible.

Constraints that shaped the decision:

- Sift is self-hosted. Every organisation already runs an identity provider (Keycloak, Entra ID,
  Auth0, Okta, …); the server must work with any OIDC-compliant issuer without vendor-specific
  code, and an administrator should have to configure only an issuer URL, a client id and
  optionally an audience.
- The web client is a browser SPA, i.e. a **public** OAuth2 client that cannot keep a secret. The
  authorization-code flow with PKCE is the current best practice for that case and needs no
  server-held credentials.
- Local development and the [end-to-end suite](../system-components/e2e-tests.md) must keep
  working with one command and without an external account, while `./kotlin check` must stay green
  on machines without network access to any provider.
- Authorization semantics (roles, quotas, creator-only cancel) are not yet understood well enough to
  design; deciding authentication first must not preclude them.

## Decision

- **The server is an OAuth2 resource server** (`spring-boot-starter-oauth2-resource-server`,
  Spring Security 7). Every request to `/api/**` must carry a JWT bearer token; the token is
  validated against the issuer's JWKS discovered from `spring.security.oauth2.resourceserver.jwt.issuer-uri`,
  and optionally against an `aud` claim via the standard `…jwt.audiences` property. No custom
  validators, no session, CSRF disabled — the API is stateless.
- **Anonymous surface is minimal and explicit**: `GET /actuator/health`, `/actuator/health/**`,
  `/actuator/info` (probes) and `GET /api/v1/auth/config`. The latter returns
  `{issuerUri, clientId, scopes}` — exactly what a public PKCE client needs — so the SPA discovers
  its provider from the server instead of carrying build-time configuration. It never returns a
  secret because the server holds none.
- **Security failures are RFC 7807 problems** like every other API error: a custom
  `AuthenticationEntryPoint`/`AccessDeniedHandler` writes `application/problem+json` (`401`/`403`)
  and the entry point still delegates to Spring's `BearerTokenAuthenticationEntryPoint` for the
  `WWW-Authenticate: Bearer …` header.
- **Flat authorization for now**: any authenticated user may do everything the API offers today
  (repositories CRUD, runs create/list/get/cancel, results, SSE watch). Roles/permissions are a
  follow-up ADR; nothing in this design assumes their absence.
- **Users are provisioned from the token on every request.** A `users` table keyed by the unique
  pair `(issuer, subject)` stores `id`, `username`, `email`, `created_at`, `last_seen_at`. A
  `OncePerRequestFilter` placed after `BearerTokenAuthenticationFilter` upserts the caller
  (`INSERT … ON CONFLICT (issuer, subject) DO UPDATE` of username/email/`last_seen_at`) and exposes
  the row to controllers via `@CurrentUser`. Username and email come from configurable claim names
  (`sift.server.auth.claims.username` / `.email`, defaults `preferred_username` / `email`); a
  missing username claim falls back to `sub`. `GET /api/v1/me` returns the provisioned profile.
- **Runs are attributed to their creator.** `agent_runs.created_by` references `users.id`; runs
  created through `POST /api/v1/agents` carry the caller, runs first seen through a status event
  (`source = EXTERNAL`) keep `null`. `AgentRunResponse` gains the additive field
  `createdBy: {id, username} | null`. `GET /api/v1/agents` and `GET /api/v1/agents/watch` accept
  `mine=true` (the caller's runs) and `createdBy=<user id>`; combining `mine=true` with a
  `createdBy` naming somebody else is a `400`. There is no `/me/agents` route — filtering keeps one
  list and one watch endpoint.
- **A real Keycloak is the development and e2e provider.** `compose.yaml` gains a `keycloak`
  service (`quay.io/keycloak/keycloak:26.5 start-dev --import-realm`, host port `8180`) that imports
  `config/keycloak/sift-realm.json`: realm `sift`, public client `sift-web` (standard flow, PKCE
  `S256`, direct access grants for the harness, audience mapper adding `aud=sift-server`), users
  `dev`/`dev` and `e2e`/`e2e`. The e2e harness obtains real tokens with the password grant and sends
  them on every request; the server's defaults (`issuer-uri http://localhost:8180/realms/sift`,
  `audiences sift-server`, `client-id sift-web`) point at this realm so `./kotlin run --module server`
  works out of the box.
- **Server tests mock only the decoder.** A `@Primary JwtDecoder` in the test tree turns a
  base64url JSON claim set into a `Jwt`; the real filter chain, entry point and provisioning filter
  stay in place, and MockMvc slices use `SecurityMockMvcRequestPostProcessors.jwt()`.

## Alternatives

- **Keep deferring (ADR 0014 as is)**: the SPA work needs a login contract now, and attribution
  cannot be retrofitted once anonymous runs pile up; rejected.
- **Static bearer token** (`sift.server.auth.token` from a Secret): trivial to implement but one
  shared credential for every client, no identity, no attribution, leaks into scripts and docs;
  rejected (it was the "cheap" option ADR 0014 already flagged as deferred, not chosen).
- **Backend-for-frontend / confidential client with server-side session** (Spring's
  `oauth2-client` login, cookie session): needs a client secret in the server, sticky or shared
  sessions, CSRF protection and a redirect dance the API-first clients (IDE plugins, MCP) cannot
  use; a resource server serves browser and non-browser clients alike. Rejected.
- **Opaque tokens with introspection**: the only variant that would require a client secret on the
  server; JWTs are supported by every candidate provider. Not implemented, documented as a future
  extension if a provider only issues opaque tokens.
- **Bundle an identity provider (Dex, embedded Keycloak) with the deployment**: adds an operational
  component every installation must run even though organisations already have an IdP; Keycloak is
  only a *development* convenience here, not a product dependency.
- **Kubernetes-native authentication** (TokenReview per request): couples clients to cluster
  identities, unusable for a browser SPA; rejected as in ADR 0014.
- **Query-parameter tokens for native `EventSource`**: leaks tokens into logs and proxies; the SPA
  must use a fetch-based SSE client that can set the `Authorization` header.
- **`/api/v1/me/agents` instead of `mine=true`**: duplicates list and watch endpoints and their
  parameters; a filter composes with `kind`/`phase`/`repositoryId`/`agentId`.

## Consequences

- Deployments must provide an OIDC issuer (`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`)
  and a public client id (`SIFT_SERVER_AUTH_CLIENT_ID`); the server fails to start without a client
  id and rejects every API call until the issuer is reachable for JWKS discovery. No new Secret
  entries are needed (`k8s/manifests/server/configmap.yaml` carries the keys).
- Every API client — the future SPA, IDE plugins, scripts, the e2e harness — needs a token. The SPA
  bootstraps from `GET /api/v1/auth/config`; machine clients obtain tokens from the provider by
  whatever grant the administrator enables. Access-token expiry applies to long-lived SSE streams
  only at connect time; a stream authenticated once is not cut when the token expires.
- Audit and "my runs" views are now possible; `created_by` is `null` for `EXTERNAL` runs and for
  runs recorded before this migration (`V2__users.sql`).
- One extra upsert per authenticated request (`last_seen_at`); negligible at current scale and
  easy to throttle later.
- Existing paths and DTOs are unchanged as promised in ADR 0014; the only additive changes are
  `createdBy`, the `mine`/`createdBy` query parameters and the two new endpoints.
- Local development gains a Keycloak container (≈10–40 s cold start); `docker compose up -d --wait
  keycloak` before starting the server, or use the token-free actuator endpoints to check liveness.
- Follow-ups: roles/permissions and creator-only cancel (separate ADR), opaque-token introspection,
  throttling of the per-request upsert, and the Ingress/TLS story that ADR 0014 left to the
  operator — authentication does not remove the need for TLS in front of the server.
