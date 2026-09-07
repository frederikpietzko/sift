# ADR 0012: Server-managed repository credentials via encrypted storage and mirrored Secrets

Date: 2026-09-07

## Status

Accepted

## Context

Reviews of private repositories need a Git access token in the review Pod. Until now the operator
could only inject a single cluster-wide token (`sift.operator.secrets.git-token`), which every
review shares regardless of repository. The server now owns repository registration through
`/api/v1/repositories` and must accept per-repository tokens without ever exposing them again
through the API, while the operator must keep its rule of referencing Secrets only and never
reading their contents (ADR 0007, operator execution contract).

Options considered for where the token lives:

- plaintext or reversible-hashed in Postgres, read by the operator — the operator gains a
  database dependency and the token still has to reach the Pod somehow;
- only in a Kubernetes Secret, with Postgres holding just the name — the server cannot recreate
  the Secret after a namespace wipe and cannot rotate keys without asking the user again;
- encrypted in Postgres as the source of truth, mirrored into a Secret the review Pod can mount.

## Decision

- `TokenCipher` encrypts tokens with AES-256-GCM (random 12-byte IV per encryption, 128-bit
  tag) using the key from `SIFT_SERVER_ENCRYPTION_KEY` (base64, exactly 32 bytes; startup fails
  otherwise). `repositories.token_ciphertext` / `token_iv` are the source of truth.
- The server owns one Opaque Secret per repository with a token: `sift-repo-<repository id>`
  (`sift.server.secret-prefix` + UUID) in `sift.server.namespace`, key `token`, labelled
  `app.kubernetes.io/managed-by: sift-server` and `sift.org/repository-id: <id>`. It is written
  with server-side apply (field manager `sift-server`, force conflicts) on create and rotate and
  deleted when the token is cleared or the repository is removed. `repositories.secret_name`
  records the mirrored name.
- `CodeReview.spec.credentialsSecretRef` (`{name, key = "token"}`) is an optional reference to
  that Secret. The server's CR builder (Step 4) fills it from `RepositoryService.secretRef(id)`;
  the operator maps it to `SIFT_REVIEW_AUTH_TOKEN` via a non-optional `secretKeyRef`. When the
  CR has no reference the operator falls back to the cluster-wide `secrets.git-token`.
- The API never returns the token; `RepositoryResponse` exposes only `hasToken` and
  `secretName`. `RepositoryService` has no decrypt entry point — the plaintext is only handled
  on the write path between request and Secret.
- Deleting a repository is refused (`409`) while any of its `agent_runs` is in a non-terminal
  phase, because running Pods still reference the Secret.

## Alternatives

- Operator reads tokens from the server database: two components sharing a schema and the
  operator gaining Postgres credentials, contrary to ADR 0011's separation.
- Secret as the only store: no recovery after namespace loss and no server-side rotation; also
  prevents future non-Kubernetes agent runtimes from obtaining the token.
- External secret manager (Vault, ESO): appropriate later, but adds an operational dependency the
  self-hosted baseline should not require.

## Consequences

- Encryption-key rotation is not yet supported; changing `SIFT_SERVER_ENCRYPTION_KEY` makes stored
  tokens undecryptable and they must be re-entered. A re-encryption command is a follow-up.
- The server ServiceAccount needs `get/create/patch/delete` on `secrets` in its namespace; the
  review Pod ServiceAccount needs no additional rights because the kubelet resolves `secretKeyRef`.
- Secret writes happen inside the database transaction; a failed apply rolls back the row but a
  failed rollback after a successful apply can leave an orphan Secret. Orphans are identifiable by
  the `sift.org/repository-id` label and are cleaned up by a reconciliation follow-up.
- The Secret holds the plaintext token as Kubernetes normally does; cluster-level protection
  (etcd encryption, RBAC) remains the administrator's responsibility.
