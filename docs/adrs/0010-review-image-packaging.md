# ADR 0010: Secret-free review image packaging and separate acceptance

## Status

Accepted for packaging. The published candidate is **not** an accepted review execution.

## Context

The agent requires native Git and external tool utilities, but does not require a compiler,
package manager or general-purpose network CLI in its Kubernetes runtime. Ignoring a file
in Git does not prevent Kotlin packaging from including it in the main or dependency JARs.
The remaining external SHA/identity contract is absent and must not be implemented implicitly
by supplying unknown Spring properties.

## Decision

- Build with the repository's checksum-verified Kotlin wrapper (0.12.0), configured Kotlin
  2.4.10/Spring Boot 4.1.1/JDK 25, and executable-JAR packaging with `runtimeClasspathMode: jars`.
  Root `.dockerignore` excludes local profiles, kubeconfig, credentials, outputs and VCS data
  **before** the builder packages any module. Recursively inspect nested JAR resources.
- Pin Temurin builder/JRE and Ubuntu image indexes by digest. Use the fixed Ubuntu snapshot
  `20260905T000000Z` for package inputs. This is a repeatable dependency recipe, not a claim
  of byte-identical JAR timestamps or a vendored/offline Maven repository. Publish immutable
  unique tags and deploy the registry digest; never silently overwrite an existing tag.
- Assemble the final image from `scratch`, copying only the JRE, selected Git/tool binaries,
  their shared libraries, CA roots, Git templates, licenses and scanner package metadata.
  Keep only the Java launcher, excluding even the JRE's unexpected `jwebserver` launcher.
  The Linux assembly helper uses `ldd`; it is never shipped in the final image.
- Run exec-form Java as UID/GID 10001. Jobs supply read-only root, bounded writable scratch,
  resource limits, no service-account token, dropped capabilities and RuntimeDefault seccomp.
  Git HTTPS still needs libcurl/cryptographic libraries; excluding the `curl` executable
  does not remove Git's network capability. SSH Git, Git LFS, compiler/build commands and
  arbitrary helper scripts are not supported by this minimal image.
- Preserve mandatory `SPRING_CONFIG_ADDITIONAL_LOCATION`, tested against the published
  artifact/image with the existing test-only initializer. Never activate `local`, replace
  packaged defaults, or silently retry using a different configuration mechanism.
- Keep the image reference in `sift.operator.review.image` in operator application YAML,
  not builders. The current default is explicitly a published arm64 packaging candidate;
  changes affect new generations only after operator restart.
- Permit explicit `candidate-*` publication to finish independent container work while
  the external contract is missing. Normal publication rejects missing packaged identity
  members. Neither field presence nor image publication proves pinned checkout; the final
  gate also needs the external owner's behavioral tests and a real matching review/event.
- Scan the OS/dependencies and image secrets; block publication on detected secrets or
  unrecognized OS metadata. Report unresolved CVEs rather than implying a clean scanner
  result or silently changing shared application dependencies.

## Boundary and consequences

Consume the existing [advisor boundary](../system-components/tool-allowlist-advisor.md) and
[ADR 0005](0005-enforce-agent-tool-allowlists.md) unchanged. This image is hardened, not a
sandbox or airgap, and no NetworkPolicy or agent business-logic changes are part of this work.
The runtime probe creates a temporary Pod, not a review Job; only the separate acceptance
workflow applies a CodeReview and waits for its operator-created Job plus dedicated AMQP receipt.
See [workflow](../system-components/code-review-image.md) and
[recorded evidence](../validation/code-review-image-2026-09-06.md).