# ADR 0006: CodeReview Execution Contract and Operator Bootstrap

Date: 2026-09-06

## Status

Accepted

## Context

Manually applied CodeReviews need a stable identity for each requested execution and an
operator that can later recover owned resources after restarts. Branch names alone cannot
identify the reviewed code. Spring Boot 4 support must come from the pinned SDK starter,
without custom application lifecycle management or CRD-installation privileges.

## Decision

Add `k8s/operator` using the shared Spring/detekt templates and Java Operator SDK Spring
starter 6.6.0. Let the starter create, start and stop the operator and client. Keep the
application non-web, leave CRD installation disabled and do not close its context after
startup. Do not add a placeholder reconciler merely to start the SDK in this bootstrap.

Extend the existing CodeReview version with required `baseBranch` and full `commitSha`.
Use `<CR UID>:<generation>` as execution identity; unchanged spec application is not a new
execution. Preserve the phase enum, adding observed generation, identity, child name/UID
references, timestamps and standard Kubernetes conditions to status.

Bind trusted operator configuration through `OperatorProperties`. Require a nonblank
`sift.operator.review.image` from the operator YAML/environment; accept full tag and digest
references without a hardcoded builder fallback. Bootstrap validation covers namespace,
image, bounded deadline, nonblank budgets and Secret references. Configuration is a
startup snapshot; downstream provisioning must not mutate already scheduled executions.

Define the SHA/execution configuration and event handoff in the
[operator component contract](../system-components/code-review-operator.md). Agent checkout,
binding and event-contract changes remain externally owned. Supplying properties does not
implement pinned checkout. Keep the existing advisor and its boundary documentation
unchanged.

## Consequences

Existing CRs/manifests must supply the two new required spec fields before admission or
subsequent updates; no automatic branch-tip default or migration is provided. The schema
is generated from the model and tested for drift. Schema admission does not replace runtime
validation and ownership checks in the future reconciler.

Step 1 builds and tests the starter-managed bootstrap but does not yet schedule Jobs.
Generation replacement, dependent ordering, immutable snapshots, durable terminal status,
RBAC, packaged configuration and local connectivity need their subsequent implementations
and evidence. The final gate remains blocked until the external SHA/identity contract is
present and verified in the published agent artifact.
