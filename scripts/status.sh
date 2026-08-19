#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <run-directory>" >&2
  exit 2
fi

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_directory="$1"
if [[ "$run_directory" != /* ]]; then
  run_directory="$repository_root/$run_directory"
fi

if [[ ! -f "$run_directory/state.json" ]]; then
  echo "Run state not found: $run_directory/state.json" >&2
  exit 3
fi

count_json() {
  local directory="$1"
  if [[ ! -d "$directory" ]]; then
    echo 0
    return
  fi
  find "$directory" -type f -name '*.json' | wc -l | tr -d ' '
}

echo "Run: $run_directory"
echo "Answers: $(count_json "$run_directory/answers")"
echo "Deterministic-check files: $(count_json "$run_directory/checks")"
echo "Judgments: $(count_json "$run_directory/judgments")"
echo "Report available: $([[ -f "$run_directory/report/report.md" ]] && echo yes || echo no)"
echo "State:"
sed -n '1,80p' "$run_directory/state.json"
printf '\n'
