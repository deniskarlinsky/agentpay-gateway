#!/usr/bin/env bash
# PreToolUse hook: protects pinned-version files and other sensitive paths.
# Exit code 2 + stderr message = block the tool call.
set -euo pipefail

command -v jq >/dev/null 2>&1 || { echo "jq is required for this hook. Install jq and re-run." >&2; exit 0; }

INPUT=$(cat)
TOOL_INPUT=$(echo "$INPUT" | jq -r '.tool_input // {}')
FILE_PATH=$(echo "$TOOL_INPUT" | jq -r '.file_path // .path // ""')

# Protect generated signing keys
if [[ "$FILE_PATH" == *"signing-key"* || "$FILE_PATH" == *".env"* && "$FILE_PATH" != *".env.example"* ]]; then
  echo "Blocked: $FILE_PATH is a secret/credential file. Never commit or edit via the agent." >&2
  exit 2
fi

# Soft warning (non-blocking) for libs.versions.toml — Stack policy says no version changes,
# but legitimate additions of new pinned deps are allowed. We log the intent.
if [[ "$FILE_PATH" == *"libs.versions.toml"* ]]; then
  echo "Note: editing libs.versions.toml. Stack policy: no -M/-RC/-SNAPSHOT, no version bumps for spring-boot/spring-ai/java." >&2
fi

exit 0
