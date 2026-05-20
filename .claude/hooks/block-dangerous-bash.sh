#!/usr/bin/env bash
# PreToolUse hook for Bash: blocks dangerous commands and stack-policy violations.
set -euo pipefail

command -v jq >/dev/null 2>&1 || { echo "jq is required for this hook. Install jq and re-run." >&2; exit 0; }

INPUT=$(cat)
CMD=$(echo "$INPUT" | jq -r '.tool_input.command // ""')

# Destructive operations
if echo "$CMD" | grep -qE '(^|[^A-Za-z0-9_])(rm -rf /|rm -rf \*|rm -rf ~|git reset --hard|git push --force|DROP TABLE|DROP DATABASE)'; then
  echo "Blocked: dangerous command pattern detected: $CMD" >&2
  exit 2
fi

# Java preview flags — hard stack-policy violation
if echo "$CMD" | grep -qE 'enable-preview'; then
  echo "Blocked: --enable-preview is a stack-policy violation. Java 21 LTS stable features only. See CLAUDE.md §4." >&2
  exit 2
fi

# Forbidden gradle dep operations that would pull SNAPSHOT/M/RC
if echo "$CMD" | grep -qE '(spring-boot:4|spring-ai:2\.0|StructuredTaskScope)'; then
  echo "Blocked: command references a forbidden version/API. See CLAUDE.md §4 and §9." >&2
  exit 2
fi

exit 0
