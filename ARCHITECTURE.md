# Webhook Processor — Architecture & Operations Guide

## Overview

A Kubernetes-native system that receives GitHub webhook events, enriches them via sidecars, queues them in Redis, and processes them as K8s Jobs with concurrency control and deduplication.

This project demonstrates:
- **Kubernetes Jobs**: dynamically created workloads that run to completion
- **Sidecar pattern**: multiple containers in a single pod sharing network, storage, and credentials
- **Producer/consumer queue**: Redis-backed with dedup and concurrency limiting
- **Multi-language job**: Node.js orchestrator invoking a compiled Go binary

---

## System Architecture

```
                          ┌──────────────────┐
                          │   User / GitHub   │
                          │   POST /api/webhook│
                          └────────┬─────────┘
                                   │
                                   ▼
┌──────────────────────────────────────────────────────────────────┐
│                                                                  │
│   Pod: webhook-processor                                         │
│   Namespace: webhook-demo                                        │
│                                                                  │
│   ┌────────────────────────┐  ┌───────────────┐  ┌────────────┐ │
│   │  webhook-service       │  │  enrichment   │  │  audit     │ │
│   │  (Spring Boot, :8080)  │  │  sidecar      │  │  sidecar   │ │
│   │                        │  │  (Python/Flask │  │  (Python/  │ │
│   │  • Webhook receiver    │  │   :5000)      │  │   Flask    │ │
│   │  • Queue producer      │  │               │  │   :5001)   │ │
│   │  • Queue consumer      │──│  • PR metadata│  │            │ │
│   │  • K8s Job launcher    │  │  • File stats │  │  • Event   │ │
│   │  • Web UI dashboard    │  │  • Complexity │  │    logging │ │
│   │                        │──│    scoring    │  │  • Shared  │ │
│   │                        │  │  • Risk level │  │    volume  │ │
│   │                        │  │  • Reviewer   │  │    writer  │ │
│   │                        │  │    suggestion │  │            │ │
│   └───────────┬────────────┘  └───────────────┘  └────────────┘ │
│               │                                                  │
│   Shared resources:                                              │
│   • Network: all containers on localhost (pod network)           │
│   • Volume:  /shared/data (emptyDir)                             │
│   • Secrets: /shared/credentials (K8s Secret mount)              │
│   • Env:     GITHUB_TOKEN, API_KEY from Secret                   │
│                                                                  │
└───────────────┼──────────────────────────────────────────────────┘
                │
        ┌───────┴───────┐
        │               │
        ▼               ▼
┌──────────────┐  ┌─────────────────────────────────────────────┐
│              │  │  K8s Jobs (created dynamically)              │
│  Redis       │  │  Namespace: webhook-demo                    │
│  (:6379)     │  │  Max concurrent: 2                          │
│              │  │                                              │
│  • Queue     │  │  ┌───────────────────────────────────────┐  │
│    (List)    │◄─┤  │  Job: pr-review-{repo}-{pr}-{id}     │  │
│  • Dedup     │  │  │                                       │  │
│    (SET+TTL) │  │  │  Node.js (processor.js)               │  │
│  • Active    │  │  │    │                                  │  │
│    jobs      │  │  │    ├─ Reads config from env vars      │  │
│    (Hash)    │  │  │    ├─ Spawns Go binary (analyzer)     │  │
│  • Job       │  │  │    ├─ Collects analysis results       │  │
│    history   │  │  │    ├─ Generates review verdict        │  │
│    (List)    │  │  │    └─ Reports results back to Redis   │  │
│  • Event     │  │  │                                       │  │
│    log       │  │  │  Go analyzer binary                   │  │
│    (List)    │  │  │    ├─ Phase 1: Clone repo     (1s)    │  │
│              │  │  │    ├─ Phase 2: Parse AST      (2s)    │  │
│              │  │  │    ├─ Phase 3: Static analysis (3s)   │  │
│              │  │  │    ├─ Phase 4: Security check  (2s)   │  │
│              │  │  │    └─ Phase 5: Generate review (1s)   │  │
│              │  │  │    Total: ~9 seconds                  │  │
│              │  │  └───────────────────────────────────────┘  │
└──────────────┘  └─────────────────────────────────────────────┘
```

---

## Components in Detail

### 1. Spring Boot Webhook Service (`webhook-service/`)

The central orchestrator. Runs as the main container in the webhook-processor pod.

**Responsibilities:**
- **HTTP endpoint** (`POST /api/webhook`): receives webhook payloads, validates, deduplicates, enriches via sidecar, and queues to Redis
- **Queue consumer** (`ConsumerService`): polls Redis every 3 seconds, checks active job count against the concurrency limit (2), and launches K8s Jobs for queued events
- **Job lifecycle** (`JobLauncherService`): uses the Fabric8 Kubernetes client to create Job resources, monitors their status, and moves completed jobs to history
- **Dashboard** (`GET /`): serves a single-page UI for submitting test events and monitoring system state
- **Status API** (`GET /api/status`): returns queue depth, active jobs, job history, and event log as JSON

**Key design decisions:**
- Fabric8 client talks to the K8s API using the pod's ServiceAccount token (mounted automatically)
- RBAC scoped to `batch/jobs` and `pods/logs` in the `webhook-demo` namespace only
- Consumer runs on a `@Scheduled` fixed-delay loop — not event-driven — so it naturally rate-limits itself

### 2. Enrichment Sidecar (`sidecar-enrichment/`)

Python Flask service running on port 5000 inside the same pod.

**What it does:**
- Receives the raw webhook event from the Spring app via `POST /enrich` on localhost
- Simulates calling the GitHub API to gather PR metadata:
  - Files changed, lines added/removed
  - Complexity score (low/medium/high)
  - Risk level (safe/moderate/elevated/critical)
  - Auto-suggested reviewers
  - Estimated review time
- Writes enrichment data to the shared volume (`/shared/data/enrichment-*.json`) so the audit sidecar can see it
- Uses the shared `GITHUB_TOKEN` from the K8s Secret

**Why a sidecar?** In production, enrichment might call external APIs with rate limits, need its own scaling characteristics, or be written by a different team. The sidecar pattern keeps it loosely coupled while sharing the pod's network and credentials.

### 3. Audit Sidecar (`sidecar-audit/`)

Python Flask service running on port 5001 inside the same pod.

**What it does:**
- Receives audit events from the Spring app via `POST /audit` on localhost
- Logs entries to stdout (captured by K8s logging) and to a shared file (`/shared/data/audit.log`)
- Reads enrichment files written by the enrichment sidecar from the shared volume
- Exposes `GET /audit/log` for retrieving recent entries

**Demonstrates:**
- Shared volume: reads files written by the enrichment sidecar
- Shared credentials: has access to the same K8s Secret (`api-key`)
- Shared network: all communication happens over localhost within the pod

### 4. Job Processor (`job-processor/`)

A multi-language container image built with a multi-stage Dockerfile.

**Stage 1 — Go analyzer (`analyzer/main.go`):**
- Compiled as a static Linux binary in the Docker build
- Accepts PR number, repository, and SHA as command-line arguments
- Runs a 5-phase simulated analysis (~9 seconds total):
  1. Repository cloning (1s)
  2. AST parsing (2s)
  3. Static analysis (3s)
  4. Security pattern checking (2s)
  5. Review generation (1s)
- Outputs structured JSON to stdout with: score, issues, suggestions, approval verdict
- Progress logs go to stderr

**Stage 2 — Node.js orchestrator (`processor.js`):**
- Entry point for the K8s Job container
- Reads job parameters from environment variables (set by the Spring app when creating the Job)
- Spawns the Go `analyzer` binary as a child process
- Parses the JSON output from the analyzer
- Generates a PR review (verdict, comments, suggestions)
- Reports results back to Redis so the Spring app can track completion

**Why Node calling Go?** This demonstrates a realistic pattern where an orchestration layer (Node) coordinates a compute-intensive binary (Go). The Go binary could be a linter, SAST tool, or ML model — anything that benefits from compiled performance.

### 5. Redis

Standard Redis 7 Alpine image. Used as:
- **Queue**: Redis List (`webhook:queue`) with LPUSH/RPOP semantics
- **Dedup store**: Redis keys with TTL (`dedup:{repo}:{pr}:{action}`, 30s TTL)
- **Active job tracker**: Redis Hash (`jobs:active`)
- **Job history**: Redis List (`jobs:history`, last 50 entries)
- **Event log**: Redis List (`events:log`, last 100 entries)

---

## Deduplication Strategy

Two layers of dedup protect against duplicate processing:

### Layer 1: Time-window dedup (TTL-based)
When an event arrives, a Redis key `dedup:{repo}:{pr}:{action}` is set with a 30-second TTL using `SETNX`. If the key already exists, the event is rejected as a duplicate. This handles rapid-fire webhook retries from GitHub.

### Layer 2: Queue-presence check
Before enqueueing, the system scans the current Redis queue for an event with the same repository, PR number, and action. If found, the event is rejected. This catches cases where the TTL has expired but the event hasn't been consumed yet.

Both checks happen before any enrichment or queueing, keeping the fast path fast.

---

## Concurrency Control

The consumer (`ConsumerService`) enforces a maximum of 2 concurrent K8s Jobs:

1. Every 3 seconds, the consumer wakes up
2. It first checks all active jobs against the K8s API to update statuses (succeeded/failed)
3. It counts active jobs in Redis
4. If `activeCount < maxConcurrentJobs`, it dequeues up to `(max - active)` events
5. For each dequeued event, it creates a K8s Job via the Fabric8 client

This means when you send 5 events:
- 2 start processing immediately as K8s Jobs
- 3 wait in the Redis queue
- As jobs complete (~9s each), the consumer picks up waiting events
- Total processing time for 5 events: ~27 seconds (3 batches of 2-2-1)

---

## Kubernetes Resources

| Resource | Name | Namespace | Purpose |
|----------|------|-----------|---------|
| Namespace | `webhook-demo` | — | Isolation for all resources |
| Deployment | `redis` | webhook-demo | Queue and state store |
| Service | `redis` | webhook-demo | ClusterIP for Redis access |
| Deployment | `webhook-processor` | webhook-demo | Main app + 2 sidecars (3 containers) |
| Service | `webhook-processor` | webhook-demo | NodePort 30080 for external access |
| ServiceAccount | `webhook-processor` | webhook-demo | Identity for K8s API access |
| Role | `job-launcher` | webhook-demo | Permission to create/manage Jobs |
| RoleBinding | `webhook-processor-job-launcher` | webhook-demo | Binds SA to Role |
| Secret | `webhook-credentials` | webhook-demo | Shared credentials |
| Job (dynamic) | `pr-review-*` | webhook-demo | Created per webhook event |

---

## Shared Resources Between Containers

The webhook-processor pod demonstrates three types of sharing:

### Shared Network (localhost)
All three containers in the pod share the same network namespace. The Spring app calls:
- `http://localhost:5000/enrich` → enrichment sidecar
- `http://localhost:5001/audit` → audit sidecar

No service discovery or DNS needed — it's just localhost with different ports.

### Shared Volume (emptyDir)
An `emptyDir` volume mounted at `/shared/data` in all three containers:
- Enrichment sidecar writes `enrichment-{pr}-{timestamp}.json` files
- Audit sidecar reads those files and writes `audit.log`
- Spring app could read these too (mount is available)

The emptyDir lives for the lifetime of the pod and is cleaned up when the pod is deleted.

### Shared Credentials (Secret)
A K8s Secret `webhook-credentials` is:
- Mounted as files at `/shared/credentials` in all containers
- Injected as environment variables (`GITHUB_TOKEN`, `API_KEY`) via `secretKeyRef`

All containers get the same credentials without needing separate Secret management.

---

## Container Images

| Image | Base | Size (approx) | Build |
|-------|------|----------------|-------|
| `webhook-service:latest` | Eclipse Temurin 17 JRE Alpine | ~200MB | Multi-stage Maven build |
| `sidecar-enrichment:latest` | Python 3.12 slim | ~60MB | pip install |
| `sidecar-audit:latest` | Python 3.12 slim | ~60MB | pip install |
| `job-processor:latest` | Node 20 Alpine + Go binary | ~120MB | Multi-stage: Go build → Node runtime |

---

## Running the System

### Prerequisites
- Rancher Desktop (or any local K8s with container builds)
- `kubectl` configured to talk to your local cluster
- `nerdctl` or `docker` available on PATH

### Quick Start

```bash
# 1. Build all 4 container images
./scripts/build-all.sh

# 2. Deploy everything to the webhook-demo namespace
./scripts/deploy.sh

# 3. Open the dashboard
open http://localhost:30080

# 4. Send a burst of 5 events to see concurrency in action
./scripts/test-burst.sh
```

### What to Observe

After sending a burst of 5 events:

1. **Dashboard** shows queue depth spike to 5, then drop as events are consumed
2. **Active Jobs** shows 2 jobs running simultaneously (the concurrency limit)
3. **Event Log** shows all 5 events with QUEUED status and enrichment metadata
4. After ~9 seconds, the first 2 jobs complete → 2 more start
5. After ~18 seconds, those complete → the last 1 starts
6. After ~27 seconds, all 5 are in Job History as SUCCEEDED

Try sending the same event again within 30 seconds — it will be deduplicated.

### Viewing Logs

```bash
# Spring Boot app (webhook receiver, consumer, job launcher)
./scripts/logs.sh webhook-service

# Enrichment sidecar (metadata enrichment calls)
./scripts/logs.sh enrichment-sidecar

# Audit sidecar (audit trail)
./scripts/logs.sh audit-sidecar

# Redis
./scripts/logs.sh redis

# All PR review job pods
./scripts/logs.sh jobs
```

### Manual Webhook Submission

```bash
curl -X POST http://localhost:30080/api/webhook \
  -H 'Content-Type: application/json' \
  -d '{
    "action": "opened",
    "repository": "acme/backend",
    "pr_number": 42,
    "pr_title": "Add user authentication",
    "sender": "octocat",
    "head_branch": "feature/auth",
    "base_branch": "main",
    "head_sha": "a1b2c3d4"
  }'
```

### Teardown

```bash
./scripts/teardown.sh
```

This deletes the entire `webhook-demo` namespace, which cascades to all resources within it.

---

## API Reference

### POST /api/webhook
Submit a webhook event for processing.

**Request body:**
```json
{
  "action": "opened",
  "repository": "acme/backend",
  "pr_number": 42,
  "pr_title": "Add user authentication",
  "sender": "octocat",
  "head_branch": "feature/auth",
  "base_branch": "main",
  "head_sha": "a1b2c3d4"
}
```

**Responses:**
- `202 Accepted` — event queued with enrichment data
- `200 OK` with `"status": "deduplicated"` — duplicate detected

### GET /api/status
Returns full system state: queue depth, active jobs, job history, event log.

### GET /api/health
Health check endpoint. Returns `{"status": "healthy"}`.

---

## File Structure

```
git_webhook_processor/
├── webhook-service/              # Spring Boot main application
│   ├── src/main/java/com/demo/webhook/
│   │   ├── WebhookProcessorApplication.java
│   │   ├── controller/WebhookController.java
│   │   ├── service/QueueService.java
│   │   ├── service/ConsumerService.java
│   │   ├── service/JobLauncherService.java
│   │   ├── client/SidecarClient.java
│   │   ├── config/RedisConfig.java
│   │   └── model/
│   │       ├── WebhookEvent.java
│   │       └── JobRecord.java
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── static/index.html       # Dashboard UI
│   ├── pom.xml
│   └── Dockerfile
├── sidecar-enrichment/            # Python enrichment sidecar
│   ├── app.py
│   ├── requirements.txt
│   └── Dockerfile
├── sidecar-audit/                 # Python audit sidecar
│   ├── app.py
│   ├── requirements.txt
│   └── Dockerfile
├── job-processor/                 # Node.js + Go job container
│   ├── processor.js               # Node orchestrator
│   ├── package.json
│   ├── analyzer/                  # Go analysis binary
│   │   ├── main.go
│   │   └── go.mod
│   └── Dockerfile                 # Multi-stage build
├── k8s/                           # Kubernetes manifests
│   ├── namespace.yaml
│   ├── secrets.yaml
│   ├── rbac.yaml
│   ├── redis.yaml
│   └── webhook-deployment.yaml
├── scripts/                       # Operations scripts
│   ├── build-all.sh
│   ├── deploy.sh
│   ├── teardown.sh
│   ├── test-burst.sh
│   └── logs.sh
└── ARCHITECTURE.md                # This file
```
