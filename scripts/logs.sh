#!/usr/bin/env bash
set -euo pipefail

CONTAINER="${1:-}"

echo "=== webhook-demo logs ==="

if [ -z "$CONTAINER" ]; then
    echo "Usage: $0 <container>"
    echo "  Containers: webhook-service, enrichment-sidecar, audit-sidecar, redis"
    echo ""
    echo "Pod status:"
    kubectl -n webhook-demo get pods
    echo ""
    echo "Recent jobs:"
    kubectl -n webhook-demo get jobs
    echo ""
    echo "Showing webhook-service logs (default)..."
    CONTAINER="webhook-service"
fi

case "$CONTAINER" in
    redis)
        kubectl -n webhook-demo logs -l app=redis -f
        ;;
    webhook-service|enrichment-sidecar|audit-sidecar)
        kubectl -n webhook-demo logs -l app=webhook-processor -c "$CONTAINER" -f
        ;;
    jobs)
        echo "Showing logs for all PR review jobs:"
        for pod in $(kubectl -n webhook-demo get pods -l app=pr-review-job -o name 2>/dev/null); do
            echo "--- $pod ---"
            kubectl -n webhook-demo logs "$pod" 2>/dev/null || echo "(no logs)"
        done
        ;;
    *)
        echo "Unknown container: $CONTAINER"
        exit 1
        ;;
esac
