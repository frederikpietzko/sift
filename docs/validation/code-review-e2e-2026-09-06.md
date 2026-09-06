# Sample-PR E2E acceptance gate — 2026-09-06

Result: **PASS**. This record covers the final manual gate from
[the image workflow](../system-components/code-review-image.md#final-manual-sample-pr-gate),
executed with `k8s/local/acceptance.py` against the supplied `kind-kind` arm64 cluster.
It supersedes the "blocked" status in the [candidate evidence](code-review-image-2026-09-06.md).

## Prerequisite closed: SHA/identity contract

The previously missing agent contract was implemented in this repository:

- `ReviewProperties` binds required `sift.review.commit-sha` and `sift.review.execution-id`.
- `GitCheckoutService` resolves `origin/<branch>` after fetch, fails with
  `CommitShaMismatchException` unless it equals the requested SHA, then checks out that commit
  detached and diffs `baseBranch...branch`. `GitCheckoutServiceTest` covers the happy path
  (HEAD equals the requested SHA) and the deliberate mismatch (no temp directory left behind).
- `CodeReviewCompletedEvent` carries `commitSha` and `executionId`; `ReviewAgent.toEvent`
  populates them from the bound properties. Serialization tests assert the stable field set.

Checks run before packaging: `./kotlin test` for `events`, `shared`, `code-review`
(3 + 21 + 19 tests, all passing) and `./kotlin check detekt` (successful).

## Published image

| Item | Value |
|---|---|
| Repository | `jbfpietzko/shift-code-review-agent` |
| Tag | `e2e-20260906-arm64-3a1b0931` |
| Digest | `sha256:3a1b0931077c0bb2d0462c4d8ba065c1e57c8892b76169d1d80dbf6af4dd29e4` |
| Artifact SHA-256 | `c5d0c7b65ed9127c603532388078e7b5c910adfdc732a47ace75b47d1b2782ed` |
| Platform | `linux/arm64` |
| Audit | `missingIdentityMembers: []`; filesystem, configuration-location, precedence, missing-file-fails and packaged-tool checks passed |
| Scan | Trivy: 8 unresolved vulnerabilities (unchanged set from the candidate record), no scanner-reported secrets |

Publication used `image.py publish` **without** `--candidate`, i.e. identity members were required.
`k8s/operator/resources/application.yaml` now defaults `sift.operator.review.image` to this digest.

## Cluster preparation

- `kubectl apply -f k8s/manifests/crds/codereviews.sift.org-v1.yml` (CRD `configured`).
- `dev.py --context kind-kind apply` (namespace, operator ServiceAccount/Role/RoleBinding,
  review ServiceAccount, fixed-upstream bridges) and `dev.py rbac`: 29 prepared-ServiceAccount
  checks passed; host Secret read separately allowed.
- Operator started on the host via `SIFT_REVIEW_IMAGE=<digest> dev.py --context kind-kind run`
  (Spring Boot 4.1.1, Java Operator SDK 5.5.0, fabric8 7.8.0, namespace `sift-dev`).
- RabbitMQ password supplied to the gate through `SIFT_VALIDATION_RABBITMQ_PASSWORD` from a
  trusted process environment; no literal was placed on the command line or in evidence.

## Gate evidence (nonsecret)

Resolved from GitHub at run time for
[ebfs-jpa PR #1](https://github.com/frederikpietzko/ebfs-jpa/pull/1):

| Field | Value |
|---|---|
| repositoryUrl | `https://github.com/frederikpietzko/ebfs-jpa.git` |
| branch / baseBranch | `ebf` / `main` |
| commitSha (head) | `2d1c3a6fc1c2d46b7429edac88519f40e6aea5ef` |
| pullRequest | `1` |

Operator-owned execution:

| Field | Value |
|---|---|
| CR name / UID / generation | `sample-pr-75fb8d934d20` / `04c7d970-5663-4fda-925f-cbae356a228b` / `1` |
| executionId | `04c7d970-5663-4fda-925f-cbae356a228b:1` |
| ConfigMap | `sample-pr-75fb8d934d-5e3ca5681a6d32da-g1` (`dab5a06a-99f6-4b80-a79b-24fc394c3360`) |
| Job | `sample-pr-75fb8d934d-5e3ca5681a6d32da-g1` (`851d70f8-a278-408a-95cc-1835f79ddf5f`) |
| status.phase | `SUCCESS`; condition `Ready=True`, reason `Completed`, observedGeneration `1` |
| status.commitSha | `2d1c3a6fc1c2d46b7429edac88519f40e6aea5ef` |
| startedAt / completedAt | `2026-09-06T18:57:52Z` / `2026-09-06T18:58:03Z` |
| Event consumed | yes, on a dedicated exclusive queue bound to `sift.events` / `code-review.completed` **before** the CR was applied; identity matched repository, branches, PR, SHA and executionId |
| Unchanged reapply | generation and Job identity preserved; no additional owned Job (`unchangedApplyDidNotRerun: true`) |

The gate applied **only** the `CodeReview`; the operator created the ConfigMap and Job with the
exact published digest and the mandatory mounted configuration containing the SHA and execution
ID. The Job's own log confirms checkout completed for the pinned SHA (1970-character diff),
the model returned a structured result (2 findings, not copied here), and the event was published.

Machine-readable evidence: `build/review-acceptance/evidence.json` and `requested-pr.json`
(ignored build output). The CR and its owned resources were retained in `sift-dev` for inspection.

## Not covered

No GitHub review is posted. Vulnerability findings still require operator risk review; the
dependency upgrades listed in the candidate record were not made here. The gate proves the
operator-scheduled path for one PR on one arm64 kind cluster; it is not a load or resilience test.
