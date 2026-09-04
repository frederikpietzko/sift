# System Architecture

This document describes the Sift system architecture (source: [architecture.png](architecture.png)).

Sift is an Open Source Agentic Code Review Platform designed for self-hosting and deployment on Kubernetes.

## Overview

- **Clients** (Sift VSCode, Sift IJ Plugin, Sift Web UI, and possibly a Coding Agent) let users manage reviews and see results. They talk to the **Sift Server** (partly via **MCP**).
- The **Sift Server** persists agent sessions and results in **Postgres** and applies Custom Resources (CRs) via the **k8s API**.
- The **Sift Operator** watches the k8s API and schedules, monitors & updates jobs — spawning **reviewer** and **security scanner** agent jobs.
- Agents publish their results to **RabbitMQ** (see [ADR 0001](adrs/0001-use-rabbitmq-as-message-queue.md)); the Sift Server consumes results and status updates from it.
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
| reviewer | Code review agent job; publishes results to the MQ |
| Security Scanner | Security review agent job; publishes results to the MQ |
| RabbitMQ | Message queue transporting agent results, status updates, and VCS events (integrated via Spring AMQP, see [ADR 0001](adrs/0001-use-rabbitmq-as-message-queue.md)) |
| VCS Adapter | Integration with VCS providers: fetches PRs & responses, posts comments, emits PR/thread events |
| GitHub / GitLab / CodeBerg | Supported VCS providers |
