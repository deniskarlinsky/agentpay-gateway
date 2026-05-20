#!/usr/bin/env bash
# PostToolUse hook: validates stack policy after edits to build-related files.
# Exit code != 0 surfaces a warning back to Claude (not blocking).
set -euo pipefail

command -v jq >/dev/null 2>&1 || { echo "jq is required for this hook. Install jq and re-run." >&2; exit 0; }

INPUT=$(cat)
FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // .tool_input.path // ""')

# Only act on build files
case "$FILE_PATH" in
  *build.gradle.kts|*libs.versions.toml|*settings.gradle.kts)
    ;;
  *)
    exit 0
    ;;
esac

VIOLATIONS=""

# Check for unstable versions
if grep -nE '"[^"]*-(M[0-9]+|RC[0-9]*|SNAPSHOT)"' "$FILE_PATH" 2>/dev/null; then
  VIOLATIONS+="Found -M/-RC/-SNAPSHOT version in $FILE_PATH. "
fi

# Check for forbidden major-version bumps
if grep -nE 'spring-boot\s*=\s*"4\.' "$FILE_PATH" 2>/dev/null; then
  VIOLATIONS+="Spring Boot 4.x is out of stack policy. "
fi

if grep -nE 'spring-ai\s*=\s*"2\.' "$FILE_PATH" 2>/dev/null; then
  VIOLATIONS+="Spring AI 2.x is out of stack policy. "
fi

if grep -nE 'java\s*=\s*"(22|23|24|25)"' "$FILE_PATH" 2>/dev/null; then
  VIOLATIONS+="Java version != 21 LTS is out of stack policy. "
fi

if [[ -n "$VIOLATIONS" ]]; then
  echo "Stack policy violation detected: $VIOLATIONS See CLAUDE.md §4. Revert these changes." >&2
  exit 2
fi

exit 0
