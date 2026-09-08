# Sift web workspace

pnpm monorepo for the browser-facing parts of Sift:

| Package            | Path                  | Purpose                                                                                                                                                                 |
| ------------------ | --------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `@sift/api-client` | `packages/api-client` | Framework-agnostic, typed client for the server API. Generated from [`api/openapi.yaml`](../api/openapi.yaml). Also the input for the future VSCode / IntelliJ plugins. |
| `@sift/web`        | `apps/web`            | React 19 + Vite SPA (Tailwind v4, shadcn/ui, TanStack Router + Query, `oidc-client-ts`).                                                                                |

The Kotlin toolchain (`./kotlin check`) does **not** build this workspace; run the pnpm commands below (CI does the same).

## Prerequisites

- Node 22+ (`.nvmrc`) and pnpm 10+ (`corepack enable` or `npm i -g pnpm`).
- Easiest path: `tools/dev.sh` in the repo root starts the Compose stack, the operator, the server and this dev
  server in one go (see `tools/dev.env` for the development defaults). The rest of this section describes the manual
  equivalent.
- For `pnpm dev`: the Compose stack (`docker compose up -d` in the repo root) and the server
  (`./kotlin run -m server`) listening on `http://localhost:8080`. The Vite dev server proxies `/api` there
  (override with `SIFT_API_URL=http://host:port pnpm dev`).
- **"Sign-in unavailable"** means the SPA could not load `GET /api/v1/auth/config`. Check what the proxy target
  answers (`curl -i http://localhost:8080/api/v1/auth/config`): a `404` from a foreign JSON body means another
  application occupies that port — stop it or use `SERVER_PORT=8099 tools/dev.sh` (which also refuses to start on an
  occupied port), a connection error means no server is running.
- **"Sign-in failed"** after Keycloak redirected back means the browser could not exchange the code for a token.
  Usually the realm is stale: the dev client `sift-web` needs `webOrigins: ["*"]` (CORS) — Keycloak imports
  `config/keycloak/sift-realm.json` only when the container is created, so recreate it after changing the file:
  `docker compose rm -sf keycloak && docker compose up -d --wait keycloak`.

## Commands

Run from `web/`:

| Command                           | What it does                                                                                 |
| --------------------------------- | -------------------------------------------------------------------------------------------- |
| `pnpm install`                    | Install all workspace dependencies (use `--frozen-lockfile` in CI).                          |
| `pnpm dev`                        | Start the SPA on <http://localhost:5173> with the `/api` proxy.                              |
| `pnpm generate`                   | Regenerate `packages/api-client/src/generated/schema.d.ts` from `api/openapi.yaml`.          |
| `pnpm generate:check`             | Fail if the committed TypeScript types are stale (run in CI).                                |
| `pnpm lint` / `pnpm format:check` | ESLint (flat config, typescript-eslint, react-hooks) / Prettier.                             |
| `pnpm typecheck`                  | `tsc --noEmit` in every package.                                                             |
| `pnpm test`                       | Vitest in every package (api-client: node; web: jsdom + Testing Library + MSW).              |
| `pnpm build`                      | `tsc` build of the api-client into `dist/` and `vite build` of the SPA into `apps/web/dist`. |
| `pnpm check`                      | All of the above in CI order.                                                                |

Filter to one package with `pnpm --filter @sift/api-client <script>` or `pnpm --filter @sift/web <script>`.

## Contract flow

1. Change a controller/DTO in `server/`.
2. `SIFT_UPDATE_OPENAPI=true ./kotlin test -m server --include-classes org.sift.server.api.OpenApiContractTest`
   rewrites `api/openapi.yaml` (see [`api/README.md`](../api/README.md)).
3. `pnpm generate` refreshes `schema.d.ts`; `pnpm typecheck` shows every consumer that has to adapt.
4. Commit the spec, the generated types and the adapted code together. `pnpm generate:check` guards the pair.

## Authentication in the SPA

`apps/web/src/auth/` owns everything OIDC; nothing else in the app talks to `oidc-client-ts`.

1. `AuthProvider` fetches the anonymous `GET /api/v1/auth/config` (`issuerUri`, `clientId`, `scopes`) and builds a
   `UserManager` (authorization code + PKCE, `redirect_uri` = `<origin>/callback`, silent renew, session storage).
   The Keycloak dev realm already allows `http://localhost:*` redirects and `*` web origins (the token exchange is a
   cross-origin `fetch` from the SPA), so `pnpm dev` works with `dev/dev`.
2. `RequireAuth` (used by the pathless `_authenticated` layout route) renders the shell for a signed-in user and
   otherwise calls `signinRedirect` with the current URL in the OIDC `state`; `/callback` finishes the flow and
   navigates back to it.
3. `AuthProvider` installs the token provider and the 401 handler of the API client singleton (`src/api/client.ts`):
   every request carries `Authorization: Bearer …`; on a `401` the app tries one silent renew and otherwise drops the
   session and redirects to login. Data hooks therefore never handle authentication themselves.
4. `useAuth()` exposes `status` (`initializing | anonymous | authenticated | error`), `user`, `login`, `logout`,
   `getAccessToken`. The header shows the username from `GET /api/v1/me` (`useMe`).

Tests never hit an identity provider: `AuthProvider` accepts a `createClient` factory and `src/test/fake-auth-client.ts`
provides an in-memory `AuthClient`; `renderApp('/runs', { authClient })` renders the real route tree with a memory
history.

## Feature screens

Each screen follows the same layering so the IDE plugins can copy it:

- `src/api/hooks/<resource>.ts` — TanStack Query hooks (`useRepositories`, `useCreateRepository`,
  `useUpdateRepository`, `useDeleteRepository`, …). Mutations invalidate via `queryKeys.<resource>.all` and seed the
  detail cache from the response; they do **not** catch errors — `SiftApiError` propagates to the component.
- `src/features/<resource>/` — screen-specific components: table, `react-hook-form` + `zod` dialogs, delete
  confirmation. Server `ProblemDetail`s (400 validation, 409 conflicts) render inline in the dialog through
  `ApiErrorAlert` (`src/components/form/`), successes emit a `sonner` toast (`<Toaster>` lives in `__root.tsx`).
- `src/routes/_authenticated/<resource>/index.tsx` — page composition: loading/error/empty states + dialog state.

Repositories: the token is write-only (`hasToken` badge only); the edit dialog can rotate it or tick “Clear the stored
token” (`clearToken: true`), never both — mirroring `RepositoryService.update`.

Runs (`/runs`, `/runs/$runId`): filters (`kind`, `phase`, `repositoryId`, `mine`) and `page` live in the URL search
params (`validateSearch` with zod, `src/features/runs/run-schema.ts`) and map 1:1 to `GET /api/v1/agents` query
parameters. `useAgentRunWatch` (`src/api/hooks/agents.ts`) keeps the caches live: it runs `watchAgentRuns` from the
api-client (bearer token, `Last-Event-ID` reconnects and fresh tokens come for free) and patches
`queryKeys.agents.detail(id)` plus every cached `queryKeys.agents.lists` page in place — rows that no longer match a
list's filters or runs the page has not seen yet trigger a refetch of that page instead. The initial `SNAPSHOT` only
refreshes what is already cached; snapshots replayed after a reconnect are treated like `UPDATED`. The list page
narrows the stream with `kind`/`mine`, the detail page with `agentId` and stops it once the phase is terminal. "Start
review" validates the 40-hex commit SHA client-side (same regex as `CreateAgentRunRequest`) and navigates to the new
run on 202; "Cancel run" is disabled for `SUCCESS`/`FAILED`/`CANCELLED` (`isTerminalPhase`). Filter and form selects
are native `<select>`s (`components/ui/native-select.tsx`) — accessible without pointer-event polyfills in jsdom.
"Delete run" (`features/runs/delete-run-dialog.tsx`, `DELETE /api/v1/agents/{id}`) is offered per row in the table and
in the detail header; the confirmation names the run and warns that a still-active run is cancelled first and that its
review result and findings are deleted permanently. A failure keeps the dialog open with the `ProblemDetail` inline;
success toasts, drops `queryKeys.agents.detail(id)`, invalidates `queryKeys.agents.all` so every filtered/paginated
table refreshes and — from the detail page — navigates back to `/runs`.

Results (`/results`, `/results/$resultId`): the list keeps `repositoryUrl`, `commitSha`, `agentRunId` and `page` in the
URL (`GET /api/v1/results`); the free-text filters are applied on submit, the `agentRunId` chip is set by the run detail
page. The detail page loads `GET /api/v1/results/{id}` (summary + every finding) and renders the summary and finding
suggestions as Markdown (`components/markdown.tsx`, `react-markdown` + GFM — raw HTML from the model is escaped, never
rendered). Findings are grouped by file (`groupFindingsByFile` in `features/results/result-helpers.ts`: groups ordered
by their most severe finding, findings by severity then line). `severity`/`file` filters live in the URL too and, when
set, fetch `GET /api/v1/results/{id}/findings` so the server does the filtering; the unfiltered result feeds the file
select and the per-severity counts.

## Production image (`sift-web`)

`apps/web/Dockerfile` builds the SPA and serves it with `nginxinc/nginx-unprivileged`; `apps/web/nginx.conf` adds
the SPA history fallback, immutable caching for `/assets/` and the same-origin `/api/` + `/v3/api-docs` proxy to
`SIFT_SERVER_UPSTREAM` (default `http://sift-server:8080`) with buffering off and a 1h read timeout for the SSE
stream. The upstream is resolved per request, so the container starts before the server exists. Build context is
this directory:

```shell
docker build -f apps/web/Dockerfile -t sift-web:dev .
docker run --rm -p 8081:8080 -e SIFT_SERVER_UPSTREAM=http://host.docker.internal:8080 sift-web:dev
```

Kubernetes manifests live in [`k8s/manifests/web/`](../k8s/manifests/web); the component is described in
[`docs/system-components/web-ui.md`](../docs/system-components/web-ui.md).

## `@sift/api-client` in a nutshell

```ts
import { createSiftClient, watchAgentRuns, SiftApiError } from '@sift/api-client'

const client = createSiftClient({
  baseUrl: 'https://sift.example.com', // defaults to window.location.origin
  getAccessToken: async () => userManager.getUser().then((u) => u?.access_token ?? null),
  onUnauthorized: () => signinRedirect(),
})

const { data: runs } = await client.GET('/api/v1/agents', { params: { query: { mine: true } } })

try {
  await client.DELETE('/api/v1/repositories/{id}', { params: { path: { id } } })
} catch (e) {
  if (e instanceof SiftApiError && e.isConflict) showProblem(e.problem) // RFC 9457 body
}

const abort = new AbortController()
void watchAgentRuns(client, {
  signal: abort.signal,
  query: { mine: true },
  onEvent: (event) => (event.type === 'SNAPSHOT' ? replace(event.run) : patch(event.run)),
})
```

- Every non-2xx response becomes a thrown `SiftApiError` (`status`, `problem: ProblemDetail`). Pass
  `throwOnError: false` to get openapi-fetch's `{ data, error, response }` instead.
- `watchAgentRuns` streams `GET /api/v1/agents/watch` with `fetch` (so the bearer token can be sent — `EventSource`
  cannot), parses the `text/event-stream` frames (multi-line `data:`, `:heartbeat` comments, `id:`), reconnects with
  `Last-Event-ID` and a freshly resolved token, and stops when the signal is aborted or a 400/403/404 is returned.
- The package has no React dependency; `apps/web/src/api` owns the TanStack Query hooks.

## Layout

```
web/
├─ package.json, pnpm-workspace.yaml, tsconfig.base.json, eslint.config.js, .prettierrc.json, .nvmrc
├─ packages/api-client/
│  ├─ scripts/generate.mjs        openapi-typescript runner (+ --check)
│  ├─ src/generated/schema.d.ts   committed, generated types
│  ├─ src/{client,errors,sse,types,index}.ts
│  └─ test/{client,sse}.test.ts
└─ apps/web/
   ├─ Dockerfile, nginx.conf      sift-web image: node build → nginx-unprivileged (SPA + /api proxy)
   ├─ vite.config.ts              TanStack Router plugin, Tailwind, /api proxy
   ├─ vitest.config.ts            jsdom, src/test/setup.ts (MSW)
   ├─ components.json             shadcn/ui configuration
   └─ src/
      ├─ routes/                  file-based routes (routeTree.gen.ts is generated by the Vite plugin and committed)
      │  ├─ callback.tsx          OIDC redirect target
      │  └─ _authenticated/       RequireAuth + AppShell layout; runs/, repositories/, results/
      ├─ auth/                    AuthProvider, useAuth, RequireAuth, UserManager factory
      ├─ components/ui/           shadcn/ui primitives (dialog, alert-dialog, table, checkbox, sonner, …)
      ├─ components/form/         FormField (label + control + error wiring), ApiErrorAlert (ProblemDetail)
      ├─ components/layout/       AppShell, UserMenu, PageHeader
      ├─ components/theme/        ThemeProvider (light/dark/system), ThemeToggle
      ├─ features/repositories/   table, create/edit dialogs (zod schema), delete confirmation, tests
      ├─ features/runs/           filters, table + pagination, phase badge, details, cancel, delete, start-review, tests
      ├─ features/results/        filters, table, summary card, findings grouped by file, severity badge, tests
      ├─ components/markdown.tsx  react-markdown wrapper for agent-produced text
      ├─ api/                     client singleton (token provider + 401 handler), QueryClient, query keys, hooks
      ├─ lib/                     cn(), formatDateTime()
      └─ test/                    MSW server/handlers, fake auth client, render helpers (renderApp)
```
