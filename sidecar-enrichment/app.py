import hashlib
import json
import os
import random
from datetime import datetime
from flask import Flask, request, jsonify

app = Flask(__name__)

# Shared credentials (mounted from K8s secret)
GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN", "demo-token-not-set")
SHARED_DATA_PATH = os.environ.get("SHARED_DATA_PATH", "/shared/data")


@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "healthy", "service": "enrichment-sidecar"})


@app.route("/enrich", methods=["POST"])
def enrich():
    """Simulate metadata enrichment for a PR webhook event.
    In production this would call GitHub API to get file diffs, contributor info, etc."""
    data = request.get_json(silent=True) or {}

    repo = data.get("repository", "unknown")
    pr_number = data.get("pr_number", data.get("prNumber", 0))
    head_sha = data.get("head_sha", data.get("headSha", "unknown"))

    # Simulate GitHub API enrichment
    seed = hashlib.md5(f"{repo}:{pr_number}:{head_sha}".encode()).hexdigest()
    random.seed(seed)

    files_changed = random.randint(1, 25)
    lines_added = random.randint(5, 500)
    lines_removed = random.randint(0, 200)
    complexity = random.choice(["low", "medium", "high"])
    risk_level = random.choice(["safe", "moderate", "elevated", "critical"])
    labels = random.sample(
        ["bugfix", "feature", "refactor", "docs", "tests", "security", "performance", "breaking-change"],
        k=random.randint(1, 3),
    )
    reviewers_suggested = random.sample(
        ["alice", "bob", "charlie", "diana", "eve"],
        k=random.randint(1, 2),
    )

    enrichment = {
        "files_changed": files_changed,
        "lines_added": lines_added,
        "lines_removed": lines_removed,
        "complexity": complexity,
        "risk_level": risk_level,
        "labels": labels,
        "suggested_reviewers": reviewers_suggested,
        "estimated_review_time_minutes": files_changed * 3 + (1 if complexity == "high" else 0) * 15,
        "enriched_at": datetime.utcnow().isoformat() + "Z",
        "github_token_present": GITHUB_TOKEN != "demo-token-not-set",
    }

    # Write enrichment to shared volume for audit sidecar
    try:
        os.makedirs(SHARED_DATA_PATH, exist_ok=True)
        filepath = os.path.join(SHARED_DATA_PATH, f"enrichment-{pr_number}-{datetime.utcnow().strftime('%Y%m%d%H%M%S')}.json")
        with open(filepath, "w") as f:
            json.dump({"event": data, "enrichment": enrichment}, f, indent=2)
    except Exception as e:
        app.logger.warning(f"Failed to write to shared volume: {e}")

    return jsonify(enrichment)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
