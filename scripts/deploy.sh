#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
K8S_DIR="$PROJECT_DIR/k8s"

echo "=== Deploying webhook-demo to Kubernetes ==="

# Apply manifests in order
echo "--- Creating namespace ---"
kubectl apply -f "$K8S_DIR/namespace.yaml"

echo "--- Creating secrets ---"
kubectl apply -f "$K8S_DIR/secrets.yaml"

echo "--- Creating RBAC ---"
kubectl apply -f "$K8S_DIR/rbac.yaml"

echo "--- Deploying Redis ---"
kubectl apply -f "$K8S_DIR/redis.yaml"

echo "--- Waiting for Redis to be ready ---"
kubectl -n webhook-demo rollout status deployment/redis --timeout=60s

echo "--- Deploying webhook-processor (with sidecars) ---"
kubectl apply -f "$K8S_DIR/webhook-deployment.yaml"

echo "--- Waiting for webhook-processor to be ready ---"
kubectl -n webhook-demo rollout status deployment/webhook-processor --timeout=120s

echo ""
echo "=== Deployment complete ==="
echo ""
echo "Pods:"
kubectl -n webhook-demo get pods -o wide
echo ""
echo "Services:"
kubectl -n webhook-demo get svc
echo ""
echo "Dashboard URL: http://localhost:30080"
echo ""
echo "To submit a test webhook:"
echo "  curl -X POST http://localhost:30080/api/webhook \\"
echo "    -H 'Content-Type: application/json' \\"
echo '    -d '"'"'{"action":"opened","repository":"acme/backend","pr_number":42,"pr_title":"Add auth","sender":"octocat","head_branch":"feature/auth","base_branch":"main","head_sha":"abc123"}'"'"
