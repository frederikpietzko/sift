# ADR 0009: Explicit local kind identity and fixed dependency bridges

## Status

Accepted.

## Context

The operator runs on the development host while review Jobs run in an existing kind
namespace. Model, search and messaging services already run on the host/Compose. Jobs
cannot use their own loopback to reach those dependencies; the token-bearing model URL
must retain its path without copying credentials into CRs or generated configuration.

## Decision

- Use only the supplied root `.kubeconfig` with explicit current-context and local-kind
  node checks. Reuse `sift-dev` and never destroy/replace clusters or install CRDs implicitly.
- Keep operator ServiceAccount/Role/RoleBinding under `k8s/manifests/operator/` and local
  namespace, review ServiceAccount and shared bridges under `k8s/manifests/local/`.
  Test the prepared ServiceAccount separately; the host JVM still uses host credentials.
- Grant CodeReview get/list/watch and status update; ConfigMap/Job get/list/watch/create/delete;
  Pod get/list/watch. No direct Secret access, CRD installation, Pod deletion, or cluster scope.
- Use three ClusterIP Services with one fixed-upstream HAProxy TCP workload. Preserve all
  request paths and streamed bytes; use 25-hour idle timeouts, no traffic logging, and a
  pinned image with nonroot/read-only/dropped-capability execution. Prefer IPv4 for Docker
  Desktop host routing; actual IPv6 upstream forwarding failed on the validated machine.
- Probe actual protocols from a Pod. Only when direct routing fails, enable a foreground
  source-CIDR-filtered host relay with explicit interface and fixed loopback destinations.
  Do not provide CONNECT/SOCKS, client-supplied destinations, or an unrestricted host proxy.
- Administrator helpers provision credential Secrets via stdin without last-applied annotations.
  The operator receives only key references and nonsecret configuration. Agent location
  handling and the already integrated advisor are unchanged.
- Apply CRs manually. A scheduling-only check uses an unpullable image; successful protocol
  probes or Job creation do not stand in for the published-image/SHA/event acceptance gate.

## Consequences

The shared bridges are local infrastructure, not CR dependents; restarting them may
interrupt active connections. Loopback fallback requires machine-specific interface and
firewall validation. RBAC is additive, and the host identity remains broad. A trusted
operator able to create Jobs can indirectly mount Secrets despite denied direct reads.
No new network isolation or exactly-once guarantee is claimed. See the
[local workflow](../system-components/local-kind-development.md) for commands and evidence,
and [ADR 0005](0005-enforce-agent-tool-allowlists.md) for the unchanged advisor boundary.