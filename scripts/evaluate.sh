#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"
jar_path="target/llm-council-evaluation-1.0.0-SNAPSHOT.jar"

log() {
  printf '[%s] %-8s %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "SCRIPT" "$*"
}

log "Evaluation harness: $repository_root"

if [[ "${EVALUATION_SKIP_BUILD:-false}" != "true" ]]; then
  log "Building the harness (tests are skipped for this launcher only)."
  mvn --batch-mode --no-transfer-progress -DskipTests package
  log "Build completed: $jar_path"
elif [[ ! -f "$jar_path" ]]; then
  printf 'Cannot skip the build: %s does not exist.\n' "$jar_path" >&2
  exit 4
else
  log "Build skipped because EVALUATION_SKIP_BUILD=true."
  newer_source="$(find pom.xml src -type f -newer "$jar_path" -print -quit 2>/dev/null || true)"
  if [[ -n "$newer_source" ]]; then
    printf 'WARNING: %s is newer than %s. The JAR may be stale; rerun without EVALUATION_SKIP_BUILD=true.\n' \
      "$newer_source" "$jar_path" >&2
  fi
fi

heartbeat_seconds="${EVALUATION_PROGRESS_HEARTBEAT_SECONDS:-30}"
log "Starting command: ${*:-<none>}"
log "Long model calls will print a heartbeat every ${heartbeat_seconds}s."
exec java -jar "$jar_path" "$@"
