# Code Review Operator

`k8s/operator` is a non-web Spring Boot application using the Java Operator SDK Spring
starter 6.6.0. The starter owns the Kubernetes client and operator lifecycle; the entry
point does not close the context after startup. See
[ADR 0006](../adrs/0006-code-review-execution-contract.md).

## Implementation status

The starter now registers `CodeReviewReconciler` and watches the configured namespace.
It invokes an SDK workflow explicitly after provisioning guards: `ReviewConfigMapDependent`
precedes `ReviewJobDependent`. Both are constructor-injected Spring beans, implementing
SDK `Creator` and `GarbageCollected`, deliberately **not** `Updater`. Direct API reads
check deterministic names, ownership and generation even when informer state is delayed.
Creation uses POST (not apply), so conflicts cannot overwrite an unrelated resource.
See [ADR 0007](../adrs/0007-immutable-review-provisioning.md).

Generation replacement now foreground-cancels old Jobs and waits for their Pods before
replacing configuration. Live-resource lifecycle checks, durable terminal identities and
resource-version-guarded status writes protect against stale events and replay. See
[ADR 0008](../adrs/0008-generation-safe-review-lifecycle.md). This implements the operator
lifecycle, not the agent's external SHA/event contract or final published-image gate.

## Trusted operator configuration

`k8s/operator/resources/application.yaml` defines `sift.operator.review.image`. Its default
is now a published arm64 **packaging candidate**, not an accepted SHA-pinned reviewer (see
[image evidence](../validation/code-review-image-2026-09-06.md)). Explicit empty or
whitespace-only overrides still fail startup validation. Set a full repository reference with
a tag or digest, either in that YAML property or via `SIFT_REVIEW_IMAGE`. For example,
`registry.example.org/team/review:release-1` is a valid configuration shape, not a published
Sift release. The final gate must instead use the published
`jbfpietzko/shift-code-review-agent@sha256:<published-digest>`.

`OperatorProperties` also exposes:

- `namespace`: defaults in YAML to `sift-dev`, overridden by `SIFT_OPERATOR_NAMESPACE`;
  validated as one Kubernetes namespace, never an all-namespaces selector.
- `review.service-account`: `sift-review`; `review.deadline-seconds`: 3600 (range 1–86400).
- `review.resources`: CPU request/limit `500m`/`2`, memory request/limit `512Mi`/`2Gi`,
  scratch size limit `2Gi`. Provisioning validates positive Kubernetes quantities and
  requests no greater than limits.
- `services.model-base-url`, `services.model`, `services.searxng-url`: unset until an
  administrator configures the integration. Preserve the model proxy URL's path when
  replacing its network origin. Do not include credentials in URLs. For JB Central use
  a literal `{proxyToken}` slot, e.g. `http://model/wire/{proxyToken}/codex/openai/v1`.
  The ConfigMap substitutes only a Spring environment placeholder; the actual token
  comes from `secrets.proxy-token` as `SIFT_MODEL_PROXY_TOKEN` in the Pod.
- `services.rabbitmq-host`, `rabbitmq-port`, `rabbitmq-username`, `rabbitmq-virtual-host`:
  `rabbitmq`, `5672`, `sift`, `/` respectively. These are nonsecret defaults, not proof of
  an available bridge or administrator-provisioned broker account.
- `secrets.model-api-key`, `proxy-token`, `rabbitmq-password`, `git-token`: optional
  `{name, key}` references to administrator-provisioned Secrets in the execution namespace.
  These properties contain references only, never secret values. The operator must not
  read Secret contents.

Job builders must use this typed trusted configuration, never CR-controlled images or
arbitrary environment passthrough. Configuration is read at operator startup; future
executions pick up changes after restart, while existing execution snapshots stay immutable.
CRD installation is explicitly disabled in the starter (`javaoperatorsdk.crd.apply-on-startup=false`).

The host-run command is
`KUBECONFIG="$PWD/.kubeconfig" SIFT_OPERATOR_NAMESPACE=sift-dev SIFT_REVIEW_IMAGE=<trusted-reference> ./kotlin run --module operator`.
First validate the existing kubeconfig targets the intended kind cluster; do not replace it.
This command uses host credentials, not a Kubernetes ServiceAccount identity. Namespace
RBAC manifests under `k8s/manifests/operator/` prepare a separate operator ServiceAccount;
they do not restrict the host identity. The [local kind workflow](local-kind-development.md)
documents context checks, namespace/review identity, fixed dependency bridges, Secret
provisioning, manual CR application, and independently verified ServiceAccount permissions.
Use `python3 k8s/local/dev.py --context kind-kind run` with `SIFT_REVIEW_IMAGE` to load the
nonsecret local service/reference configuration while preserving packaged operator defaults.

## CodeReview execution contract

The `sift.org/v1alpha1`, namespaced `CodeReview` preserves `repositoryUrl`, `branch`,
optional string `pullRequest`, and phases `CREATED`, `PENDING`, `RUNNING`, `SUCCESS`, `FAILED`.
It now requires `baseBranch` and a full 40-character hexadecimal `commitSha`. Repository
and branch fields must be nonempty and contain no whitespace. Prefer lowercase Git SHAs;
the schema accepts either hexadecimal case. No credentials belong in a CR. Provisioning
accepts HTTP(S) repositories without userinfo, query or fragment, and rejects Spring
placeholders in CR fields so untrusted values cannot expand into Secret environment values.

Each `metadata.generation` represents one execution, identified by
`<metadata.uid>:<metadata.generation>`. Reapplying an unchanged spec does not request a new
execution. A new generation supersedes the prior one; cancellation must wait for its Pods
to disappear before provisioning the latest generation. Rapid updates coalesce.

Status fields are nullable until established; conditions default to an empty list:

| Field | Meaning |
|---|---|
| `observedGeneration` | Generation represented by status, not a stale event's generation |
| `executionId`, `commitSha` | Durable execution identity and requested commit |
| `jobRef`, `configMapRef` | Names and UIDs of same-namespace owned children; never adopt foreign UIDs |
| `startedAt`, `completedAt` | RFC 3339 UTC timestamp strings |
| `conditions` | Standard Kubernetes conditions (`type`, `status`, `reason`, `message`, `lastTransitionTime`, `observedGeneration`) |

The `Ready` condition is `True` only for success and `False` otherwise. Its reason and
transition time change only with meaningful state changes; raw Pod/Job messages are not
copied into status. `startedAt` records observed container start time and may be absent
when no execution was observed; `completedAt` uses Job completion time for success when
available, otherwise the time terminal status was observed.

| Phase | Reasons |
|---|---|
| `PENDING` | `Scheduled`, `CancellationInProgress`, `SchedulingFailed`, `ImagePullFailed`, `ContainerWaiting` |
| `RUNNING` | `Executing` (container running or terminated with start evidence, not merely Job `active`) |
| `SUCCESS` | `Completed` (`Complete=True` Job condition, not just a Pod exit or success counter) |
| `FAILED` | `ExecutionFailed`, `DeadlineExceeded`, `ResourcesLost`, `ConfigurationError`, `ResourceConflict` |

### Replacement and recovery

The host workflow uses one operator instance per namespace. The reconciler reads live
Jobs, ConfigMaps and Pods; validates ownership; foreground-deletes old Jobs; and retains
old ConfigMaps until **all** old Jobs/Pods disappear. Pending cancellation never blocks a
worker: Job/ConfigMap watches and five-second requeues drive progress. Pod lists provide
bounded observation even when Jobs disappear before their Pods. Stuck finalizers keep
the newest execution pending rather than permitting overlap; administrator intervention
must respect that safety boundary.

The latest CR UID, generation, resourceVersion and deletion timestamp are checked before
provisioning and before each dependent POST. Old events only trigger reconciliation; their
payload never overwrites the current generation. CR status updates and child deletes use
resource-version preconditions, so concurrent changes fail and are retried at the API level.
Terminal `SUCCESS`/`FAILED` status is never rewritten or re-executed for the same generation,
including after child deletion and operator restart. A new generation remains eligible.
Missing, deleting or UID-replaced established resources fail with `ResourcesLost`; their
original references remain in status. Existing children without a successful status write
are recovered without mutation using deterministic names and live ownership checks.

CR reads and child creation are not one atomic transaction. An update arriving immediately
after the final check may create a superseded child, which is then cancelled before its
replacement can start. Deleting both status and all child evidence erases durable history;
neither Kubernetes Jobs nor this controller provide exactly-once execution.

The lifecycle requires `update` on the status subresource and namespace Job/ConfigMap/Pod
listing, in addition to the existing SDK watches and child create/delete operations. It
does not read Secrets, force-delete Pods, or manage CR finalizers; CR deletion uses owner GC.

Generate the manifest, never edit it manually:
`./kotlin do generateCrds --module crds`. The generator synchronizes `k8s/manifests/crds`
and deletes stale output files. Tests under `k8s/operator/test` compare freshly generated
schema to the checked-in manifest, verify validation constraints and status fields, and
round-trip the model through Fabric8 serialization. The real-kind validation below covers
API admission, cancellation, immutable snapshots and owner garbage collection.

## External agent handoff (required before packaging and E2E)

The operator's immutable ConfigMap contains `application.yaml`, mounted read-only
at `/etc/sift/review/application.yaml`, with this explicit mapping:

| CR / execution source | Agent Spring property |
|---|---|
| `spec.repositoryUrl` | `sift.review.repository-url` |
| `spec.branch` | `sift.review.branch` |
| `spec.baseBranch` | `sift.review.base-branch` |
| `spec.pullRequest` (if present) | `sift.review.pull-request` |
| `spec.commitSha` | `sift.review.commit-sha` |
| `<CR UID>:<generation>` | `sift.review.execution-id` |

The **external agent owner**, not the operator, must implement and test:

1. Binding for `sift.review.commit-sha` and `sift.review.execution-id`.
2. Native Git checkout pinned to the requested SHA. Failure to fetch/resolve/check out that
   commit must fail the process; never silently review the branch tip. Validate actual
   checked-out HEAD against the requested SHA (hexadecimal case is insignificant).
3. Shared `CodeReviewCompletedEvent` fields `commitSha` and `executionId`, populated from
   the verified checkout/request identity, retaining repository, branch, base branch and
   pull request fields. Publish using exchange `sift.events`, routing key
   `code-review.completed`. Execution ID matching is exact; compare full SHA hex values.

At Step 1 implementation time, `ReviewProperties` and `CodeReviewCompletedEvent` lack
these fields. They are **blocking prerequisites**, not functionality implemented by
supplying unknown Spring keys. Agent Kotlin and shared event contracts were not changed
in this step.

Jobs use only mandatory `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/sift/review/application.yaml`.
There is no optional prefix, `SPRING_CONFIG_LOCATION`, or `local` profile. Packaged startup
validation selected and proved this mechanism against the real agent artifact; do not set
both location variables.

Job completion controls CR success. A dedicated RabbitMQ consumer established before
execution must separately prove receipt of a structured event matching repository, PR,
SHA and execution ID. Cancellation cannot retract already published events; this is not
exactly-once execution or result arbitration.

The integrated shell advisor is consumed unchanged. Its existing
[component documentation](tool-allowlist-advisor.md) and
[ADR 0005](../adrs/0005-enforce-agent-tool-allowlists.md) define the boundary. Verify it is
included in the packaged artifact; it is not a pending implementation prerequisite.
Native Git and required shell utilities remain; no network sandbox is implied.

## Job shape and Secret mapping

Jobs have `restartPolicy: Never`, `backoffLimit: 0`, one completion/parallelism, a bounded
deadline, resource budgets and no TTL cleanup. The ConfigMap is immutable and mounted
read-only with mode 0444. Pods use UID/GID/fsGroup 10001, `runAsNonRoot`, RuntimeDefault
seccomp, a read-only root filesystem, no privilege escalation, dropped ALL capabilities,
and no service-account token. One size-limited emptyDir is mounted at `/scratch` and `/tmp`;
Java temporary/home paths and the working directory point to writable scratch locations.

| Trusted Secret reference | Explicit environment variable |
|---|---|
| `model-api-key` | `OPENAI_API_KEY` |
| `proxy-token` | `SIFT_MODEL_PROXY_TOKEN` |
| `rabbitmq-password` | `SPRING_RABBITMQ_PASSWORD` |
| `git-token` | `SIFT_REVIEW_AUTH_TOKEN` |

No Secret contents are read by the operator; references are non-optional when configured.
Nonsecret model, web-search and RabbitMQ properties use explicit YAML serialization.
The model override maps to Spring AI's `spring.ai.openai.chat.options.model`.

## Provisioning validation (2026-09-06)

- `./kotlin test --include-module operator`: 35 passing tests covering schema, starter
  namespace wiring, image tag/digest overrides, mapping, ownership, security and SDK
  create-only snapshot preservation. `./kotlin check detekt` passes.
- `python3 k8s/operator/validation/provisioning_api.py --context kind-kind`: passed
  against the supplied root `.kubeconfig` and existing arm64 kind node. Proves actual
  SDK ordering, API validation, immutable Job/ConfigMap rejection, restart/image-change
  preservation, unchanged apply, new-execution image binding and owner garbage collection.
  It uses a temporary namespace without a review ServiceAccount to avoid running images
  or calling external services. It removes that namespace and stops both host operators.
  The previously absent generated CRD was installed and intentionally retained.
- Add `--lifecycle` to the provisioning command for real-API replacement and recovery
  scenarios: finalizer-held Pod cancellation, ConfigMap retention, skipped intermediate
  generations, stale version rejection, stale Job events, missing resources, status-write
  recovery and terminal durability across deletion/restart. It uses an unscheduled fixture
  Pod and API-patched Job outcomes, not an agent execution. The full flow passed in 26
  seconds locally with 35-second bounded waits; use a 120-second command timeout. Logs
  are written to `build/operator-api-validation/operator.log`. The test removes only its
  own Pod finalizer and temporary namespace, and stops its host operators.
- `python3 k8s/operator/validation/packaged_config.py`: **passed**. Staging excludes
  `application-local.yaml` before packaging. The executable nests `events-jvm.jar` and
  `shared-jvm.jar` because `agents/code-review` sets `settings.jvm.runtimeClasspathMode: jars`
  (Spring's default `classes` mode packages module outputs as Boot-unloadable directories;
  see KTC-5686). Missing mounted files fail JarLauncher with a configuration-location error.
  A temporary copy embeds test-only `ConfigProbe` via `META-INF/spring.factories` (Boot 4
  removed `DelegatingApplicationContextInitializer`, so `context.initializer.classes` and
  PropertiesLauncher `loader.path` cannot register the probe). The probe starts the real
  agent, asserts mounted `ReviewProperties` binding and precedence, retained packaged
  RabbitMQ/non-web defaults, inactive `local` profile, and advisor class presence, then
  exits before the review runner clones. Mechanism selected:
  mandatory `SPRING_CONFIG_ADDITIONAL_LOCATION` only.

Unstaged `./kotlin package --module code-review --format executable-jar` can still include
`application-local.yaml` under `BOOT-INF/classes` and inside `code-review-jvm.jar`. Never
publish that artifact; Step 5 must exclude local resources before packaging—as validation
staging already does—and rerun the same startup assertions in the final image. No image or
E2E pass is claimed here. The external SHA/execution agent contract remains a separate gate.
