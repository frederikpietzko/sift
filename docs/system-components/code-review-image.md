# Code review image and acceptance workflow

The [Dockerfile](../../agents/code-review/Dockerfile) builds a minimal Java-25-compatible
review runtime. [ADR 0010](../adrs/0010-review-image-packaging.md) records packaging decisions;
[candidate evidence](../validation/code-review-image-2026-09-06.md) records the pre-identity
candidate, and [E2E evidence](../validation/code-review-e2e-2026-09-06.md) records the **passing**
final SHA/execution/event gate for the current published image.

## Build and publish

Run from the repository root. Prerequisites: Docker/buildx, Python 3, host JDK 25 (`javac`
for test-only probes), Trivy, internet access to pinned images/Ubuntu snapshot/Kotlin/Maven,
and Docker Hub credentials already provisioned via `docker login`. Credentials remain in
the host credential store; no build argument, Docker layer, CR or ConfigMap contains them.

```shell
python3 agents/code-review/image/image.py build --platform linux/arm64
python3 agents/code-review/image/image.py audit --image sift-review:candidate
python3 agents/code-review/image/image.py scan --image sift-review:candidate
python3 agents/code-review/image/image.py publish --image sift-review:candidate \
  --tag candidate-<unique-build-id> --candidate
```

Use `linux/arm64` for the supplied kind cluster; explicitly select another platform only
when its nodes require it. Cold builds use a 300s helper budget, scans 180s, ordinary
operations 120s. Build logs and nonsecret JSON evidence live under ignored `build/review-image/`.
The wrapper and package snapshot are pinned. JAR/build timestamps can change digests on a
rebuild; deployment reproducibility comes from pulling the published immutable digest,
not from assuming two builds are byte-identical. Retain the digest from each publication.

Publication reruns artifact/filesystem/configuration/tool audits and scans, rejects known
secret findings, refuses an existing remote tag, pushes and pulls back the exact digest,
and compares it to the audited image. Docker Hub tag immutability must also be enforced
administratively for protection against other publishers/concurrent tag races. The helper
never uses `latest`. Vulnerabilities are reported and require operator risk review; publication
does not constitute vulnerability approval. Without `--candidate`, missing packaged identity
members block publication. Do not use the candidate to claim or attempt the final acceptance gate.

The image audit recursively checks all dependency JARs, preserves packaged application defaults,
and confirms the integrated advisor is present and referenced. `ConfigProbe.java` is embedded
only into a temporary test copy of the exact exported artifact, then executed in the published
image with a read-only mount. It observes real Spring startup/binding before the review runner:
mounted precedence, retained non-web/RabbitMQ defaults and advisor presence. A second startup
uses the **unmodified** published JAR and proves a missing mandatory configuration file fails.
Current SHA/execution properties are only observed in the Environment, not claimed as bound fields.

`ToolProbe.java` uses the actual packaged tool dependency: Read, Glob, Grep, and the Bash
callback with `pwd`. It also performs HTTPS Git clone/fetch/checkout/diff without a model.
Native Git, Bash, ripgrep and the selected `env`/`cat`/`sleep`/`rm` runtime utilities are explicit
exceptions to package minimization. No compiler, package manager, curl/wget, SSH or network
debugging executable is included. Java and Git retain network capabilities; see the existing
[advisor boundary](tool-allowlist-advisor.md), not a new isolation claim.

## Published image under the Job security settings

```shell
python3 agents/code-review/image/kind_probe.py --context kind-kind \
  --image 'jbfpietzko/shift-code-review-agent@sha256:<published-digest>'
```

The helper validates root `.kubeconfig`, expected kind nodes and `sift-dev`. It creates a
unique temporary ConfigMap/Pod, uses `imagePullPolicy: Always` (no kind image load), the review
ServiceAccount with token mount disabled, UID/GID/fsGroup 10001, RuntimeDefault seccomp,
capability drop, no privilege escalation, read-only root, default Job CPU/memory limits and
one bounded `emptyDir` mounted at `/scratch` and `/tmp`. The real tools/native Git run with
these settings; `/proc/self/status` confirms UID, effective capabilities, no-new-privileges
and seccomp. The helper deletes only its own resources. This is **not** a manually created
review Job and is not evidence of operator review scheduling.

## Final manual sample-PR gate

The agent now implements SHA-pinned checkout (`GitCheckoutService` fails with
`CommitShaMismatchException` when `origin/<branch>` is not the requested SHA, covered by
`GitCheckoutServiceTest`), binds `sift.review.commit-sha`/`sift.review.execution-id`, and
carries both in `CodeReviewCompletedEvent`. The published image
`jbfpietzko/shift-code-review-agent@sha256:3a1b0931077c0bb2d0462c4d8ba065c1e57c8892b76169d1d80dbf6af4dd29e4`
(tag `e2e-20260906-arm64-3a1b0931`) passed the audit with no missing identity members and
passed the gate below. Artifacts without these members still fail closed before any CR is applied.

Procedure (rerun after any agent/operator change):

1. Prepare the [local kind workflow](local-kind-development.md): namespace, bridges and
   administrator-provisioned Secrets. Operator RBAC remains under `k8s/manifests/operator/`;
   the host JVM still uses its broader host kubeconfig identity.
2. Set `sift.operator.review.image` in `k8s/operator/resources/application.yaml` to the new
   published digest (or use `SIFT_REVIEW_IMAGE`). Start exactly one operator using
   `SIFT_REVIEW_IMAGE=<digest-reference> python3 k8s/local/dev.py --context kind-kind run`.
   This executes `./kotlin run --module operator` with the explicit root kubeconfig and
   nonsecret local endpoint configuration. Never enable the agent's `local` profile.
3. Create a host-only validation environment and provide the RabbitMQ password via a trusted
   environment as `SIFT_VALIDATION_RABBITMQ_PASSWORD`, not a literal command/history entry:

   ```shell
   python3 -m venv build/acceptance-venv
   build/acceptance-venv/bin/pip install -r k8s/local/acceptance-requirements.txt
   build/acceptance-venv/bin/python k8s/local/acceptance.py --context kind-kind \
     --image 'jbfpietzko/shift-code-review-agent@sha256:<published-digest>'
   ```

The gate resolves PR #1 metadata from GitHub each time, pulls/audits the published artifact,
and rejects missing identity fields before any CR is applied. Once eligible, a dedicated
exclusive AMQP queue/consumer binds `sift.events` / `code-review.completed` **before** applying
only a uniquely named CodeReview. The existing Compose broker is reached from the host at
127.0.0.1:5672, user `sift`, vhost `/`; its password is never printed.

Passing requires current-generation owned Job/ConfigMap UIDs, exact image and mandatory
mounted configuration, Job `Complete=True`, CR `SUCCESS`, and a structured consumed event
matching repository/branches/PR/SHA/execution ID. Findings may be empty. Unchanged reapply
must preserve generation/Job identity and create no additional owned Job. The gate never
creates a review Job itself and does not post GitHub reviews.

Evidence is written to `build/review-acceptance/`; CR/resources are retained for inspection.
No secrets, prompts, findings or raw agent logs are copied into acceptance evidence. A missing
contract, timeout, failed Job or missing event is blocked/failed—not a partial pass. The
happy-path AMQP/review join was executed and passed on 2026-09-06; see the E2E evidence record.

## Offline checks

```shell
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s agents/code-review/image -p 'test_*.py'
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s k8s/local -p 'test_*.py'
./kotlin test --include-module operator
./kotlin check detekt
```