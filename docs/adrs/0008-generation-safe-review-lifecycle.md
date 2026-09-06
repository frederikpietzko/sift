# ADR 0008: Generation-safe replacement and durable review status

## Status

Accepted.

## Context

A CodeReview spec generation requests one costly execution. Informer delays, failed API
requests, operator restarts and rapid spec changes must not turn reconciliation into
whole-Job retries. Jobs and their configuration are immutable snapshots, as established
in [ADR 0007](0007-immutable-review-provisioning.md).

## Decision

- Keep the SDK ConfigMap-before-Job workflow explicitly invoked by the reconciler.
  Read the current CR and live namespace resources before eligibility decisions; do not
  interpret the triggering secondary event as the current execution. Validate controller
  owner UID, deterministic name and generation labels. Never adopt a foreign same-name
  resource. Persist observed child UIDs and preserve those identities on failure.
- Foreground-delete superseded owned Jobs, using resource-version preconditions to avoid
  deleting a concurrently replaced object. Wait for **both** Jobs and their Pods to
  disappear before deleting old ConfigMaps, also with version preconditions. Requeue
  every five seconds rather than blocking a worker. Job/ConfigMap informers accelerate
  reconciliation; Pod observation uses bounded live-list polling, including labelled
  orphan Pods after Job deletion. Do not force-remove finalizers or time out into overlap.
- Recheck CR UID, generation, deletion timestamp and resourceVersion before the workflow
  and immediately before each dependent POST. While cancellation is pending, updates
  coalesce to the newest observed generation; no queue of intermediate runs is kept.
- Terminal status is durable for its execution identity. Do not recreate deleted terminal
  children. For nonterminal executions, a missing/deleting Job or ConfigMap, a changed
  recorded child UID, a Job without its ConfigMap, or orphan current-generation Pods
  produces `FAILED/ResourcesLost`, not a retry. A new generation is still eligible.
- Recover existing immutable children after a failed status write or restart using live
  reads and deterministic identity. Kubernetes POST conflicts and other API failures are
  retried by the SDK, independently of review execution; no Job-template update is used.
- Write status only when meaningful fields change, with a resource-version-locked status
  update (PUT), not an unguarded status apply. A concurrent spec/status update rejects
  the stale write. Ready conditions contain fixed, nonsecret messages, current generation,
  reason and stable transition times. Pod execution evidence is required for `RUNNING`;
  a `Complete=True` Job condition is required for `SUCCESS`. Failure/deadline conditions,
  image-pull and scheduling problems are projected separately.
- Retain the mandatory additional Spring configuration location and immutable ConfigMap
  delivery selected and packaged-startup-validated in ADR 0007. New operator configuration
  affects future executions only. Neither configuration delivery nor lifecycle status
  implements SHA-pinned agent checkout or proves RabbitMQ consumer receipt.

## Consequences and limitations

The local workflow runs one operator instance per namespace. CR reads and child POSTs
are separate Kubernetes transactions: a spec change racing *after* the final check may
briefly create a superseded child. The next reconciliation cancels it, and replacement
still waits for its Pods. This is not a claim of transactional or exactly-once execution.
Cancellation cannot retract an already published event; consumers need execution identity.
Administratively deleting both status and all execution evidence removes the durable
history and is outside the no-replay guarantee.

Status writes require `update` on `codereviews/status`; namespace observations require
`list` on Jobs, ConfigMaps and Pods. Job/ConfigMap informers need `get/list/watch`, creation
needs `create`, cancellation/cleanup needs `delete`. No Secret read, CR finalizer,
forceful Pod deletion, or CRD installation permission is introduced. Kubernetes owner
garbage collection handles CR deletion. Host credentials remain distinct from future
operator ServiceAccount RBAC.

Fabric8 7.8.0's `lockResourceVersion` narrows its result to `ReplaceDeletable`, hiding
propagation options. The runtime operation also implements the public
`DeletableWithOptions` interface. A checked capability test combines locking with explicit
foreground propagation and fails closed if a future implementation stops supporting it.

## Verification

Operator unit tests cover cancellation order, orphan/lost/replaced resources, immutable
snapshots, stale versions, failed status writes/restarts, terminal identity, configuration
errors, scheduling/image-pull problems, execution failure and deadline expiry.

`python3 k8s/operator/validation/provisioning_api.py --context kind-kind --lifecycle`
uses the real API and controllers in an isolated temporary namespace. It verifies
immutable-template admission, owner GC, foreground deletion blocked by a finalizer-held
unscheduled Pod, coalescing, stale-status rejection, stale Job events, status recovery,
restart preservation and no replay after terminal resource deletion. Job outcome fixtures
are patched through the status API; **no review runs or final E2E acceptance is claimed**.