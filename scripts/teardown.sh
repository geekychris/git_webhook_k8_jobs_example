#!/usr/bin/env bash
set -euo pipefail

echo "=== Tearing down webhook-demo ==="

# Delete all jobs first (they may be running)
echo "--- Deleting any running jobs ---"
kubectl -n webhook-demo delete jobs --all --ignore-not-found=true 2>/dev/null || true

echo "--- Deleting namespace (removes everything) ---"
kubectl delete namespace webhook-demo --ignore-not-found=true

echo ""
echo "=== Teardown complete ==="
