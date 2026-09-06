# Local kind development

The host-run operator uses the repository-root `.kubeconfig`; review Jobs use three
fixed-upstream ClusterIP Services in `sift-dev`. This workflow does not create/reset a
cluster, replace kubeconfig, install CRDs automatically, create per-review infrastructure,
or run a server API. See [ADR 0009](../adrs/0009-local-kind-connectivity.md).

## Prerequisites and setup

Run from the repository root with Python 3.10+, `kubectl`, `kind`, Docker Desktop, and
the Kotlin toolchain available. The helpers use Python's standard library only.
An administrator supplies `.kubeconfig`, with the **expected current context** selected.
All helper API calls specify that file/context and `sift-dev` explicitly. The helpers
compare API node names to `kind get nodes --name <context-minus-kind-prefix>` and print
node architectures; build the eventual review image for that architecture.

Commands below assume `kind-kind`; explicitly substitute the intended existing kind context.
First validate it without mutations:

```shell
python3 k8s/local/dev.py --context kind-kind check
```

The administrator must install the generated CRD if it is absent. This command uses
administrator credentials, not the operator Role:

```shell
kubectl --kubeconfig "$PWD/.kubeconfig" --context kind-kind apply -f k8s/manifests/crds/codereviews.sift.org-v1.yml
docker compose up -d rabbitmq searxng
python3 k8s/local/dev.py --context kind-kind apply
python3 k8s/local/dev.py --context kind-kind apply --dry-run
python3 k8s/local/dev.py --context kind-kind rbac
```

`apply` creates the namespace if missing and reuses an existing Active namespace. It
refuses to overwrite same-name resources without its `app.kubernetes.io/managed-by:
sift-local-dev` label. It manages only the explicitly listed manifests, with no pruning
or namespace deletion. The label is an operational collision guard, not authentication.
Server dry-run validates admissions without changes; if the namespace is absent, it
validates namespace admission then stops, since namespaced admissions require its existence.
Configuration checksums roll the bridge Deployment when upstream configuration changes.
Do not change bridges during active reviews: a rollout can interrupt their connections.

## Identities and RBAC

`k8s/manifests/operator/service-account.yaml`, `role.yaml`, and `role-binding.yaml`
prepare **`system:serviceaccount:sift-dev:sift-operator`** with:

| Resource | Verbs |
|---|---|
| `codereviews.sift.org` | get, list, watch |
| `codereviews.sift.org/status` | update |
| ConfigMaps and batch Jobs | get, list, watch, create, delete |
| Pods | get, list, watch |

There are no Secret-content, CRD-write, Pod-delete, workload-update, or cluster-wide
permissions. `dev.py rbac` explicitly impersonates this ServiceAccount (including its
normal groups), checks all allowed verbs and denied operations, and separately reports
the host identity's Secret-read permission. Impersonation needs administrator permission;
a failure to impersonate is not a pass. RBAC is additive: unexpected existing bindings
can cause the denial checks to fail.

**The host JVM does not use this ServiceAccount.** It authenticates with root `.kubeconfig`,
which remains broader. Applying a RoleBinding does not restrict the host credentials.
The separate `sift-review` ServiceAccount has no RoleBinding, and Jobs and bridge Pods
disable token automount. No token is provisioned for either ServiceAccount by these helpers.
The operator is trusted: Job-creation rights can indirectly expose namespace Secrets
through Pod mounts, even though direct Secret-content API reads are denied.

## Fixed-upstream bridges

`k8s/manifests/local/bridges.yaml` contains one shared, nonroot/read-only HAProxy Deployment,
a nonsecret ConfigMap, and three ClusterIP Services. It is not a review dependent.
The HAProxy image is pinned to a multi-architecture digest.

| Job-facing endpoint | Fixed host target |
|---|---|
| `http://jb-central:19516/wire/{proxyToken}/codex/openai/v1` | `host.docker.internal:19516` |
| `http://searxng:8888` | `host.docker.internal:8888` (Compose) |
| `rabbitmq:5672` | `host.docker.internal:5672` (Compose) |

Only the JB Central **network origin** changes from the existing ignored agent-local
configuration. `/wire/{proxyToken}/codex/openai/v1` remains intact; the operator emits
`${SIFT_MODEL_PROXY_TOKEN}` into the execution ConfigMap and the Job resolves it from
a Secret. Neither bridge configuration nor access logs contain the actual token.

TCP forwarding preserves HTTP paths, headers, chunking, and AMQP bytes, without HTTP
buffering, request rewriting, retries, or client-selected upstreams. The 25-hour idle
timeouts exceed the operator's maximum 24-hour execution deadline. DNS refresh prefers
IPv4: on the validated Docker Desktop installation IPv6 DNS resolved but upstream IPv6
connections failed. The readiness probe checks the listener only, **not upstream health**.

### Optional loopback host relay

Try direct routing first. `probe --model` checks actual HTTP/AMQP/SSE behavior, not merely
a successful TCP connect. A loopback listener can accept a Docker-side TCP connection
without completing HTTP forwarding. If protocol checks show that direct routing fails
but the host service works, use `k8s/local/host_relay.py` for **only** the affected service:

```shell
python3 k8s/local/host_relay.py --bind "$HOST_INTERFACE_IPV4" \
  --allow-client "$OBSERVED_DOCKER_SOURCE_CIDR" --service model
# In another terminal:
python3 k8s/local/dev.py --context kind-kind apply \
  --relay-service model --relay-address "$HOST_INTERFACE_IPV4"
python3 k8s/local/dev.py --context kind-kind probe --model
```

Choose a specific host interface address reachable from Pods, not `localhost`, and verify
the actual Docker source address with your host firewall/network tooling. Restrict the
firewall to that source as well; Docker NAT can obscure individual Pod identities. There
is no guessed interface/CIDR, wildcard bind, automatic firewall edit, or automatic relay
startup. Repeat `--service`/`--relay-service` only for dependencies that require it.

Fixed mappings are model `29516 → 127.0.0.1:19516`, search `28888 → 127.0.0.1:8888`, and
messaging `25672 → 127.0.0.1:5672`. Source CIDRs are enforced before opening upstreams;
connections are bounded and traffic is not logged. The relay is a foreground process;
stop with Ctrl-C. A plain `apply` restores direct host routing. The validated machine
needed **no relay**; fallback streaming/source-filter behavior has offline tests, but
its interface/firewall behavior must be verified on the machine that needs it.

## Secrets and trusted operator configuration

Provision the administrator-owned `sift-local-credentials` Secret using hidden prompts:

```shell
python3 k8s/local/dev.py --context kind-kind secrets
```

Alternatively supply `OPENAI_API_KEY`, `SIFT_MODEL_PROXY_TOKEN`, and
`SPRING_RABBITMQ_PASSWORD` through a trusted process environment (not command-line literals
or shell history). An optional `SIFT_REVIEW_AUTH_TOKEN` creates `git-token`; enable its
reference in `k8s/local/operator.yaml` as `sift.operator.secrets.git-token:
{name: sift-local-credentials, key: git-token}` only when needed.

For the existing development configuration only, explicit
`secrets --from-agent-local` imports the key/token/password from the ignored
`agents/code-review/resources/application-local.yaml`. It accepts only the known simple
scalar layout and exact original model URL; changed/ambiguous syntax fails rather than
guessing. It does not copy that file, activate its profile, or package its contents.

The helper sends Secret data over stdin with create/resource-version-protected replace,
never command arguments, files, console values, or a last-applied annotation. Existing
unrelated Secret names are not touched; unspecified existing keys are preserved. Helpers
suppress raw API/upstream errors because these can echo credentials. Administrators
must protect kubeconfig and the cluster's Secret storage; base64 is not encryption.

`k8s/local/operator.yaml` is **nonsecret host-operator configuration**: service endpoints,
model selection, and Secret name/key references. It is added to the operator's normal
configuration, not to the agent image. `sift.operator.review.image` remains defined in
`k8s/operator/resources/application.yaml` via `SIFT_REVIEW_IMAGE`; builders do not hardcode
an image. The helper requires that environment override explicitly:

```shell
SIFT_REVIEW_IMAGE='jbfpietzko/shift-code-review-agent@sha256:<published-digest>' \
  python3 k8s/local/dev.py --context kind-kind run
```

Replace the placeholder with a real published digest; see the [image workflow](code-review-image.md)
for the current published image and the passing acceptance gate.
`run` validates context/namespace, removes raw credential environment variables, and
executes `./kotlin run --module operator` with `KUBECONFIG=<rootDir>/.kubeconfig` and the
nonsecret operator configuration. It rejects pre-existing `SPRING_CONFIG_*`,
`SPRING_PROFILES_*`, `SPRING_APPLICATION_JSON`, and `KUBERNETES_*` overrides that could
bypass that configuration; unset these before using the helper. Run exactly one operator per namespace and stop it
with Ctrl-C. Restart it to use configuration changes for future executions; existing
Jobs remain immutable. Do not activate `local`. Review Jobs retain their independently
validated mandatory `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/sift/review/application.yaml`;
no second agent location variable is introduced.

## Manual CodeReview application

After external SHA-pinned checkout/event identity support and the published image are
available, resolve the PR's current repository/head branch/base branch/full head SHA.
Before execution, establish the final gate's dedicated RabbitMQ validation queue.
With the operator running, apply only the CR:

```shell
python3 k8s/local/dev.py --context kind-kind apply-review --name sample-pr \
  --repository https://github.com/frederikpietzko/ebfs-jpa.git \
  --branch "$HEAD_BRANCH" --base-branch "$BASE_BRANCH" --sha "$HEAD_SHA" --pull-request 1
python3 k8s/local/dev.py --context kind-kind observe sample-pr
kubectl --kubeconfig "$PWD/.kubeconfig" --context kind-kind -n sift-dev get codereview sample-pr -w
```

`apply-review` validates literals and creates/updates **only** `CodeReview`, never a Job.
Alternatively manually apply a YAML CR with the same five required spec fields.
`observe` verifies current-generation Job/ConfigMap references and controller owner UIDs,
printing nonsecret identity evidence. It is not a `SUCCESS` or event-consumption check.
Reapplying identical arguments does not increment generation or rerun a terminal review.
Changing spec cancels the old generation and waits for its Pods before scheduling the latest.
Deleting the CR garbage-collects its owned Job/ConfigMap; shared bridges remain.

## Repeatable validation and recorded evidence

```shell
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s k8s/local -p 'test_*.py' -v
python3 k8s/local/dev.py --context kind-kind apply --dry-run
python3 k8s/local/dev.py --context kind-kind rbac
python3 k8s/local/dev.py --context kind-kind probe --model
# Stop any existing operator first; this requires an idle sift-dev namespace:
python3 k8s/local/scheduling_check.py --context kind-kind
```

`probe` creates a uniquely named, bounded, nonroot validation Pod using Python (not the
review image), with no API token. It checks Pod-to-host TCP, all Service DNS/listeners,
SearXNG HTTP, and RabbitMQ's AMQP handshake. `--model` additionally injects Secret references
and makes one small, potentially billable model request, requiring HTTP 200, SSE chunks,
and `[DONE]`. The local proxy requires `stream_options.include_usage=true`. Neither model
content nor credentials are printed. The temporary Pod is deleted in `finally` and also
has a 600-second deadline. RabbitMQ authentication and event receipt remain final-gate checks.

`scheduling_check.py` starts the actual host helper/operator using an intentionally
unpullable image and applies a uniquely named CR. It verifies owned resource identities,
mounted endpoints/Secret references, exact image selection, and unchanged apply. It
deletes only its CR and stops its process; logs are under `build/local-validation/`.
No review is executed. The existing broader lifecycle harness is still available:
`python3 k8s/operator/validation/provisioning_api.py --context kind-kind --lifecycle`.

Recorded on the supplied `kind-kind` arm64 cluster:
- Server-side manifest dry-run and 29 allowed/denied prepared-ServiceAccount checks passed.
  Host Secret reads were separately allowed, confirming the identity distinction.
- Direct Pod-to-host access worked for all three ports. SearXNG HTTP and AMQP handshake passed.
- Authenticated model path returned four SSE chunks and `[DONE]`; first event arrived in 0.77s.
- Actual operator scheduling with local configuration and unchanged apply passed; test resources
  were removed. Shared bridge resources and the administrator credential Secret remain for reuse.

This is **not** the final sample-PR gate. That gate is `k8s/local/acceptance.py`, which
implements the queue-before-CR workflow, fails closed when the packaged identity contract is
absent, and **passed** on 2026-09-06 against PR #1 with the operator started through `dev.py run`
(see [E2E evidence](../validation/code-review-e2e-2026-09-06.md)). The existing
[tool-allowlist advisor boundary](tool-allowlist-advisor.md) and
[ADR 0005](../adrs/0005-enforce-agent-tool-allowlists.md) are consumed unchanged.
These bridges add neither network-policy enforcement nor an airgap.