# Project Intent

Open Source Agentic Code Review Platform designed for self hosting and deployment on k8s.

# Tooling

Kotlin toolchain (formerly Amper), use `./kotlin --help` to explore usage.

# Architecture & Stack

Kotlin Toolchain multi-module build.

- build-config: custom plugins for Kotlin Toolchain
    - crd-generator: fabric8 crd generator to generate k8s CRDs into `k8s/manifests/crds`
    - detekt: linting tool to enforce code quality
- k8s/crds: Kotlin, Definition of CRDs as Kotlin classes with fabric8 apt
- k8s/manifests: YAML, Kubernetes manifests generated from CRDs, future home for k8s manifests for deployments, roles,
  service account, helm charts, etc.
- k8s/local: potentially helpful tools for testing operator in k8s locally
- k8s/operator: Future -> Kotlin, Spring Boot 4, java-operator-sdk, k8s-operator to manage agent jobs
- agents: Kotlin, Spring Boot 4, Spring AI; different agents to perform actions to be run as k8s jobs
    - shared: Kotlin, Spring AI; Shared code for agents, eg common advisors
    - code-review: Kotlin, Spring Boot 4, Spring AI; agent to perform code review
    - security-review: Kotlin, Spring Boot 4, Spring AI; agent to perform security review
- server: Kotlin, Spring Boot 4, fabric8-client; applies CRs to cluster, exposes API to FE
- UI out of scope atm

# Tooling & Execution

- **Build System**: Use `./kotlin` for build and run tasks
- **Linting**: Use `./kotlin check detekt`
- **Tests**: Use `./kotlin test` or `./kotlin check test`
- **Checks**: Use `./kotlin check` to run all checks
- **k8s**: k8s kind cluster running, kubeconfig at `./.kubeconfig`

# Context Pointers

- when deciding on system architecture update `docs/system-architecture.md`
- ADR should document every decision in `docs/adrs`
- when creating new system components, create documentation in `docs/system-components`
- when updating system components, update documentation in `docs/system-components`