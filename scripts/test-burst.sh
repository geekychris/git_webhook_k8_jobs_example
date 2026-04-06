#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:-http://localhost:30080}"

echo "=== Sending burst of 5 webhook events ==="
echo "This will demonstrate:"
echo "  - 2 jobs running concurrently (max capacity)"
echo "  - Remaining events waiting in queue"
echo "  - Jobs completing and freeing capacity"
echo ""

repos=("acme/backend" "acme/frontend" "acme/api" "acme/mobile" "acme/infra")
titles=("Add authentication" "Fix routing bug" "Update API docs" "Refactor data models" "Add monitoring dashboards")
branches=("feature/auth" "fix/routing" "docs/api" "refactor/models" "feature/monitoring")

for i in 0 1 2 3 4; do
    PR=$((100 + i))
    SHA=$(openssl rand -hex 4)
    echo "Sending: ${repos[$i]} PR #$PR - ${titles[$i]}"
    curl -s -X POST "$BASE_URL/api/webhook" \
        -H 'Content-Type: application/json' \
        -d "{
            \"action\": \"opened\",
            \"repository\": \"${repos[$i]}\",
            \"pr_number\": $PR,
            \"pr_title\": \"${titles[$i]}\",
            \"sender\": \"octocat\",
            \"head_branch\": \"${branches[$i]}\",
            \"base_branch\": \"main\",
            \"head_sha\": \"$SHA\"
        }" | python3 -m json.tool 2>/dev/null || echo "(response parsing failed)"
    echo ""
done

echo "=== Burst complete ==="
echo "Open http://localhost:30080 to watch jobs process"
echo ""
echo "Now try sending a duplicate (should be deduplicated):"
echo "  curl -s -X POST $BASE_URL/api/webhook -H 'Content-Type: application/json' -d '{\"action\":\"opened\",\"repository\":\"acme/backend\",\"pr_number\":100,\"pr_title\":\"Add authentication\",\"sender\":\"octocat\",\"head_branch\":\"feature/auth\",\"base_branch\":\"main\",\"head_sha\":\"test123\"}'"
