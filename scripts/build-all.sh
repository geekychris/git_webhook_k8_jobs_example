#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "=== Building all container images ==="

# Detect container runtime — prefer docker for Rancher Desktop moby engine
if command -v docker &>/dev/null && docker info &>/dev/null; then
    BUILD_CMD="docker"
    NAMESPACE_FLAG=""
    echo "Using docker"
elif command -v nerdctl &>/dev/null; then
    BUILD_CMD="nerdctl"
    NAMESPACE_FLAG="--namespace k8s.io"
    echo "Using nerdctl (Rancher Desktop containerd)"
else
    echo "ERROR: No container runtime found. Install docker or nerdctl."
    exit 1
fi

echo ""
echo "--- Building webhook-service (Spring Boot) ---"
$BUILD_CMD $NAMESPACE_FLAG build -t webhook-service:latest "$PROJECT_DIR/webhook-service"

echo ""
echo "--- Building sidecar-enrichment (Python) ---"
$BUILD_CMD $NAMESPACE_FLAG build -t sidecar-enrichment:latest "$PROJECT_DIR/sidecar-enrichment"

echo ""
echo "--- Building sidecar-audit (Python) ---"
$BUILD_CMD $NAMESPACE_FLAG build -t sidecar-audit:latest "$PROJECT_DIR/sidecar-audit"

echo ""
echo "--- Building job-processor (Node.js + Go) ---"
$BUILD_CMD $NAMESPACE_FLAG build -t job-processor:latest "$PROJECT_DIR/job-processor"

echo ""
echo "=== All images built successfully ==="
$BUILD_CMD $NAMESPACE_FLAG images | grep -E "(webhook-service|sidecar-|job-processor)" || true
