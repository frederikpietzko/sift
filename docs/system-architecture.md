# System Architecture

This document describes the Sift system architecture (source: [architecture.png](architecture.png)).

Sift is an Open Source Agentic Code Review Platform designed for self-hosting and deployment on Kubernetes.

## Overview

- **Clients** (Sift VSCode, Sift IJ Plugin, Sift Web UI, and possibly a Coding Agent) let users manage reviews and see results. They talk to the **Sift Server** (partly via **MCP**).
- The **Sift Server** persists agent sessions and results in **Postgres** and applies Custom Resources (CRs) via the **k8s API**.
- The **Sift Operator** watches the k8s API and schedules, monitors & updates jobs — spawning **reviewer** and **security scanner** agent jobs.
- Agents publish their results to **RabbitMQ** (see [ADR 0001](adrs/0001-use-rabbitmq-as-message-queue.md)); the Sift Server consumes results and status updates from it.
  - Event payloads (e.g. `CodeReviewCompletedEvent`) are defined in the shared **`events`** module, so producers (agents) and the consumer (Sift Server) share one contract.
  - **`agents/shared`** provides the messaging abstraction (`EventPublisher` backed by RabbitMQ, publishing to the `sift.events` topic exchange) and shared agent tools, including the default-enabled **SearXNG** web-search tool (see [ADR 0002](adrs/0002-airgapped-agents-with-optional-searxng-web-search.md)). Operators configure their self-hosted SearXNG endpoint or explicitly disable search with `sift.tools.web-search.enabled=false`. Network isolation requires deployment-level controls; the tool setting does not enforce an airgap.
  - Shared messaging and web-search beans are discovered through Spring Boot auto-configuration; agents do not need to scan or import shared packages. Defaults back off for application-provided beans (see [ADR 0003](adrs/0003-auto-configure-shared-agent-infrastructure.md)).
  - Code review explicitly installs the shared `ToolAllowlistAdvisor` to check shell commands before execution. Shell execution defaults to denied until exact commands are configured, with denials returned as tool feedback. Non-shell tools pass through unrestricted by the advisor. This is not a filesystem or network sandbox (see [ADR 0005](adrs/0005-enforce-agent-tool-allowlists.md) and [shell command allowlist advisor](system-components/tool-allowlist-advisor.md)).
  - The code-review agent explicitly closes its Spring context after its one-shot runner completes, releasing RabbitMQ resources so the job can exit (see [ADR 0004](adrs/0004-close-one-shot-agent-context.md)).
  - For local development, RabbitMQ, Postgres, and SearXNG run via [docker-compose](../compose.yaml).
- The **VCS Adapter** integrates with **GitHub**, **GitLab**, and **CodeBerg**: it gets PRs & responses, posts comments, and publishes events (PR created, comment on PR thread) to the MQ.

## Mermaid Diagram

```mermaid
graph TB
    subgraph clients [Clients]
        vscode["Sift VSCode"]
        ij["Sift IJ Plugin"]
        webui["Sift Web UI"]
        coding["Coding Agent???"]
    end

    user(("User<br/>Manage Reviews & see results"))
    user -.-> vscode
    user --> ij
    user --> webui

    subgraph k8s [k8s]
        postgres[("Postgres<br/>Agent Sessions, Results etc")]
        server["Sift Server"]
        mcp["mcp?"]
        k8sapi["k8s API"]
        operator["Sift Operator<br/>Schedules, Monitors & Updates Jobs"]
        reviewer["reviewer"]
        security["Security Scanner"]
        mq["RabbitMQ"]
        vcs["VCS Adapter"]
    end

    github["GitHub"]
    gitlab["GitLab"]
    codeberg["CodeBerg"]

    vscode <--> server
    ij <--> server
    webui <--> server
    coding -->|request review???| mcp
    mcp --- server

    server --> postgres
    server -->|Applies CR| k8sapi
    server -->|update cr if comment on thread| k8sapi

    operator -->|watches| k8sapi
    operator -->|schedules| reviewer
    operator -->|schedules| security

    reviewer -->|publish result| mq
    security -->|publish result| mq

    mq -->|receive result & status updates| server
    mq -->|pr created & comment on pr thread| server

    vcs -->|pr created, comment on pr thread| mq
    vcs <-->|get prs & responses & post comments| github
    vcs <--> gitlab
    vcs <--> codeberg
```

## Components

| Component | Role |
|---|---|
| Sift VSCode / IJ Plugin / Web UI | Client frontends to manage reviews and see results |
| Coding Agent (???) | Potential future client requesting reviews (via MCP) |
| Sift Server | Central service; exposes API (and MCP), stores state in Postgres, applies CRs to the cluster, consumes MQ events |
| Postgres | Stores agent sessions, results, etc. |
| k8s API | Kubernetes API server; receives CRs from Sift Server, watched by the operator |
| Sift Operator | Watches CRs; schedules, monitors & updates agent jobs |
| reviewer | Code review agent job; publishes results to the MQ (see [code-review-agent](system-components/code-review-agent.md)) |
| Security Scanner | Security review agent job; publishes results to the MQ |
| RabbitMQ | Message queue transporting agent results, status updates, and VCS events (integrated via Spring AMQP, see [ADR 0001](adrs/0001-use-rabbitmq-as-message-queue.md)) |
| VCS Adapter | Integration with VCS providers: fetches PRs & responses, posts comments, emits PR/thread events |
| GitHub / GitLab / CodeBerg | Supported VCS providers |
| events (module) | Shared event contracts (`SiftEvent`, `CodeReviewCompletedEvent`, …) used by agents and the Sift Server |
| agents/shared (module) | Shared agent infrastructure: `EventPublisher` (RabbitMQ, `sift.events` exchange) and tools such as the optional SearXNG web search |
| SearXNG | Self-hosted metasearch engine backing the default-enabled agent web-search tool; part of the local compose stack (see [ADR 0002](adrs/0002-airgapped-agents-with-optional-searxng-web-search.md)) |
