# Review image evidence — 2026-09-06

## Result: packaging candidate published; final acceptance BLOCKED

- Registry: `jbfpietzko/shift-code-review-agent`
- Unique final candidate tag: `candidate-20260906-arm64-125d9061`
- Published/pulled digest: `sha256:125d9061ade606b87390456a41357b82359f417e6b472b8d33de0d57d6f5990b`
- Packaged executable SHA-256: `2a4840ed791553a6b932cba9d92b569389c937f1cc268b498eff3b48507b933d`
- Platform: `linux/arm64`; root kubeconfig context `kind-kind`, namespace `sift-dev`.
- Operator YAML uses this digest via `sift.operator.review.image`, with an explicit candidate warning.
- An earlier diagnostic candidate tag `candidate-20260906-arm64-e862d948` was also published;
  it is superseded by the final candidate above (scanner metadata/licenses/package snapshot improved).

## Verified

- Multi-stage Linux packaging with the repository Kotlin wrapper; local configuration excluded
  from the context before compilation and recursively absent from the main/nested module JARs.
- Advisor class present in shared JAR and referenced by packaged ReviewAgent; no agent Kotlin
  or advisor/boundary documentation changes.
- Registry push followed by pull by digest and identity comparison; not just a locally loaded image.
- Audit executed again using the **published digest**, including real Spring startup of the
  temporary initializer-instrumented artifact and missing-file failure of the unmodified JAR.
  `SPRING_CONFIG_ADDITIONAL_LOCATION` retains defaults and provides mounted binding/precedence;
  no fallback `SPRING_CONFIG_LOCATION` or active profile.
- Actual packaged Read/Glob/Grep/Bash `pwd` and native HTTPS Git clone/fetch/checkout/diff pass.
- No forbidden CLIs in exported image filesystem; only Java launcher retained from the JRE.
- Kind pulled the published image (`Always`) and completed the security/tool probe:
  Pod UID `27899ed0-dc23-4db9-bcf5-cc6fdc466323`, phase `Succeeded`, image ID equals the
  published digest. Nonroot UID 10001, zero effective capabilities, no-new-privileges,
  seccomp filtering, no API token, read-only root and bounded scratch were checked.
  Temporary probe Pod/ConfigMap removed; no review executed.
- Six image-helper tests, eighteen local-helper tests and thirty-five operator tests passed.
  `./kotlin check detekt`, `./kotlin check --module operator --module crds` and `git diff --check` passed.

## External prerequisite and sample PR

Live GitHub PR #1 metadata resolved:

| Field | Value |
|---|---|
| Repository | `https://github.com/frederikpietzko/ebfs-jpa.git` |
| Head branch | `ebf` |
| Base branch | `main` |
| Requested SHA | `2d1c3a6fc1c2d46b7429edac88519f40e6aea5ef` |

The actual packaged `ReviewProperties` and `CodeReviewCompletedEvent` each lack
`getCommitSha` and `getExecutionId`. Source checkout still checks out the branch tip, not
the requested SHA. The acceptance command therefore exited **1 / BLOCKED** before CR
creation or RabbitMQ consumption. Supplying these keys to Spring is not an implementation.

No reviewed-SHA assertion, operator-created **review** Job UID, current-generation CR
`SUCCESS`, matching consumed event, or unchanged-reapply success is claimed for this image.
Those remain required after the external agent owner supplies pinned checkout, binding and
correlation plus behavioral tests. The separate kind tool probe is not the final gate.

## Vulnerability/secret scan

Trivy 0.69.3 scanned the final digest: Ubuntu 24.04 (36 copied package identities) and
nested Java dependencies. **8 unresolved findings: 3 HIGH, 4 MEDIUM, 1 LOW.** No scanner-reported
secrets. Scanner absence of findings is not proof of absence or isolation. The fixed Ubuntu
snapshot plus explicit runtime library updates removed five findings from the initial candidate.

| Package | Installed | CVEs / severity | Reported fix |
|---|---|---|---|
| git | `1:2.43.0-1ubuntu7.3` | CVE-2024-52005 / MEDIUM | none reported by Ubuntu feed |
| libc6 | `2.39-0ubuntu8.8` | CVE-2026-18374 / MEDIUM | none reported by Ubuntu feed |
| com.rabbitmq:amqp-client | `5.31.0` | CVE-2026-63337 / HIGH | `5.33.0` |
| com.rabbitmq:amqp-client | `5.31.0` | CVE-2026-69219, CVE-2026-69220 / HIGH | `5.33.1` |
| com.rabbitmq:amqp-client | `5.31.0` | CVE-2026-63336 / MEDIUM; CVE-2026-61634 / LOW | `5.33.0` |
| org.jsoup:jsoup | `1.15.4` | CVE-2026-71497 / MEDIUM | `1.23.1` |

Application dependency upgrades require coordinated ownership/testing and were not silently
made here. Native Git/libc findings remain; the candidate is not approved as vulnerability-free.
Full local scanner results: `build/review-image/trivy.json`; nonsecret summary:
`build/review-image/scan-summary.json`. Re-run scans as databases and packages change.

## Reproduction

See [image workflow](../system-components/code-review-image.md). Actual commands included
`image.py build --platform linux/arm64`, `image.py publish ... --candidate`, `image.py audit`,
`image.py scan`, `kind_probe.py --context kind-kind --image <published-reference>`, and
`k8s/local/acceptance.py --context kind-kind --image <published-reference>`.
Ordinary commands used 60–120s tool timeouts; combined snapshot build/audit/scan and kind
probe used 180s. Published-digest audit measured **6.63s**. Initial Trivy database downloads
were roughly 1GB; subsequent scans use its cache. Helpers preserve detailed local build logs.