#!/usr/bin/env bash
# Stop hook: reminds about pre-commit checklist if there are uncommitted changes.
# Important: check stop_hook_active to avoid infinite loops.
set -euo pipefail

command -v jq >/dev/null 2>&1 || { echo "jq is required for this hook. Install jq and re-run." >&2; exit 0; }

INPUT=$(cat)
STOP_ACTIVE=$(echo "$INPUT" | jq -r '.stop_hook_active // false')

if [[ "$STOP_ACTIVE" == "true" ]]; then
  exit 0
fi

# Only nag if we're in a git repo with uncommitted changes
if ! git -C "${CLAUDE_PROJECT_DIR:-.}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  exit 0
fi

CHANGED=$(git -C "${CLAUDE_PROJECT_DIR:-.}" status --porcelain | wc -l | tr -d ' ')

if [[ "$CHANGED" -gt 0 ]]; then
  # Print to stderr — Claude sees this. Do NOT exit 2 unless we want to force more work.
  echo "Reminder: $CHANGED uncommitted file(s). Before committing run CLAUDE.md §5 checklist: tests, no -M/RC/SNAPSHOT, no --enable-preview, spotlessApply." >&2
fi

exit 0
