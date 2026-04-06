# Git Webhook Processor

A Kubernetes-native system that receives GitHub webhook events, enriches them via sidecars, queues them in Redis, and processes them as dynamically-created K8s Jobs with concurrency control and deduplication.

## Purpose

Simulates an automated PR review pipeline: when a pull request event arrives, the system enriches it with metadata, queues it, and launches a Kubernetes Job that runs a Go-based static analyzer to produce a review verdict (approve/request changes). A web dashboard provides real-time visibility into queue depth, active jobs, and job history.

This project demonstrates several Kubernetes patterns:
- **Sidecar containers** sharing network, volumes, and credentials within a pod
- **Dynamic K8s Job creation** via the Fabric8 Kubernetes client
- **Producer/consumer queue** backed by Redis with two-layer deduplication
- **Multi-language job execution** (Node.js orchestrator spawning a compiled Go binary)
- **RBAC** scoped to a specific namespace and resource types

## Architecture

```
GitHub/User  -->  POST /api/webhook
                      |
        +-------------v--------------+
        |  Pod: webhook-processor    |
        |                            |
        |  [Spring Boot :8080]       |   Shared: localhost network,
        |  [Enrichment Sidecar :5000]|   emptyDir volume, K8s Secrets
        |  [Audit Sidecar :5001]     |
        +------|---------------------+
               |
       +-------+--------+
       v                 v
   [Redis :6379]    [K8s Jobs]
   Queue, dedup,    pr-review-* (max 2 concurrent)
   state tracking   Node.js -> Go analyzer (~9s each)
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for full component details, data flow, Redis data structures, deduplication strategy, and concurrency control.

## Components

| Component | Language | Purpose |
|-----------|----------|---------|
| `webhook-service/` | Java 17 / Spring Boot 3.2 | Central orchestrator: webhook receiver, queue producer/consumer, K8s Job launcher, web dashboard |
| `sidecar-enrichment/` | Python 3.12 / Flask | Simulates GitHub API enrichment (file stats, complexity, risk level, reviewers) |
| `sidecar-audit/` | Python 3.12 / Flask | Audit logging to stdout and shared volume |
| `job-processor/` | Node.js 20 + Go 1.22 | K8s Job container: Node orchestrator spawns Go analyzer binary for PR review |
| `k8s/` | YAML | Kubernetes manifests (namespace, secrets, RBAC, Redis, webhook deployment) |

## Prerequisites

- Local Kubernetes cluster (Rancher Desktop, Docker Desktop with K8s, minikube, or k3s)
- `kubectl` configured for your cluster
- `docker` or `nerdctl` on PATH

## Quick Start

```bash
# 1. Build all 4 container images
./scripts/build-all.sh

# 2. Deploy to the webhook-demo namespace
./scripts/deploy.sh

# 3. Open the dashboard
open http://localhost:30080

# 4. Send a burst of 5 test events
./scripts/test-burst.sh
```

After the burst, watch the dashboard: 2 jobs run concurrently, the remaining 3 queue up, and all 5 complete in about 27 seconds across 3 batches.

## Scripts

| Script | Purpose |
|--------|---------|
| `scripts/build-all.sh` | Build all 4 container images (auto-detects docker vs nerdctl) |
| `scripts/deploy.sh` | Create namespace, secrets, RBAC, Redis, and webhook-processor deployment |
| `scripts/teardown.sh` | Delete the entire `webhook-demo` namespace and all resources |
| `scripts/test-burst.sh` | Send 5 webhook events to test concurrency and deduplication |
| `scripts/logs.sh <target>` | Stream logs (targets: `webhook-service`, `enrichment-sidecar`, `audit-sidecar`, `redis`, `jobs`) |

## API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/webhook` | POST | Submit a webhook event for processing |
| `/api/status` | GET | System state: queue depth, active jobs, history, event log |
| `/api/health` | GET | Health check |
| `/` | GET | Web dashboard UI |

### Example webhook submission

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

## Configuration

Key environment variables (set via K8s manifests):

| Variable | Default | Description |
|----------|---------|-------------|
| `REDIS_HOST` | `localhost` | Redis hostname |
| `REDIS_PORT` | `6379` | Redis port |
| `K8S_NAMESPACE` | `webhook-demo` | Namespace for job creation |
| `JOB_IMAGE` | `job-processor:latest` | Container image for K8s Jobs |
| `MAX_CONCURRENT_JOBS` | `2` | Maximum simultaneous K8s Jobs |

## Detailed Documentation

See [ARCHITECTURE.md](ARCHITECTURE.md) for:
- Full system architecture diagram
- Component deep dives with design rationale
- Redis data structure details
- Deduplication strategy (TTL + queue-presence)
- Concurrency control mechanics
- Shared resource patterns (network, volumes, secrets)
- Container image build details
- Complete file structure
