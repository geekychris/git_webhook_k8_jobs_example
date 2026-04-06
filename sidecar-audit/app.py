import json
import os
from datetime import datetime
from flask import Flask, request, jsonify

app = Flask(__name__)

SHARED_DATA_PATH = os.environ.get("SHARED_DATA_PATH", "/shared/data")
AUDIT_LOG_PATH = os.path.join(SHARED_DATA_PATH, "audit.log")


@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "healthy", "service": "audit-sidecar"})


@app.route("/audit", methods=["POST"])
def audit():
    """Log audit events to the shared volume and stdout."""
    data = request.get_json(silent=True) or {}

    entry = {
        "timestamp": data.get("timestamp", datetime.utcnow().isoformat() + "Z"),
        "action": data.get("action", "unknown"),
        "details": data.get("details", {}),
        "source": "webhook-processor",
    }

    # Log to stdout (picked up by K8s logging)
    app.logger.info(f"AUDIT: {json.dumps(entry)}")

    # Append to shared volume audit log
    try:
        os.makedirs(SHARED_DATA_PATH, exist_ok=True)
        with open(AUDIT_LOG_PATH, "a") as f:
            f.write(json.dumps(entry) + "\n")
    except Exception as e:
        app.logger.warning(f"Failed to write audit log: {e}")

    # Also read and return recent enrichment files from shared volume
    enrichments = []
    try:
        for fname in sorted(os.listdir(SHARED_DATA_PATH)):
            if fname.startswith("enrichment-") and fname.endswith(".json"):
                enrichments.append(fname)
    except Exception:
        pass

    return jsonify({
        "status": "logged",
        "entry": entry,
        "shared_enrichment_files": enrichments[-5:],  # last 5
    })


@app.route("/audit/log", methods=["GET"])
def get_audit_log():
    """Return recent audit log entries."""
    entries = []
    try:
        with open(AUDIT_LOG_PATH, "r") as f:
            for line in f:
                line = line.strip()
                if line:
                    entries.append(json.loads(line))
    except FileNotFoundError:
        pass

    return jsonify({"entries": entries[-50:]})  # last 50 entries


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5001)
