# `.claude/` — Claude Code infrastructure for AgentPay Gateway

This directory configures Claude Code beyond the rules in `CLAUDE.md`. Everything here is **optional but recommended**. The project builds and ships without it; with it, common mistakes are caught deterministically.

## Layout

```
.claude/
├── settings.json              # Hook configuration (project-level, committed)
├── hooks/                     # Shell scripts the hooks invoke
│   ├── pre-edit-guard.sh      # Blocks edits to secret files
│   ├── block-dangerous-bash.sh# Blocks rm -rf, --enable-preview, etc.
│   ├── post-edit-stack-check.sh # Validates build files against stack policy
│   └── stop-verify.sh         # Reminds about pre-commit checklist
├── skills/                    # On-demand domain knowledge
│   ├── spring-ai-1.1-gotchas/SKILL.md
│   └── saga-states/SKILL.md
└── agents/                    # Subagents Claude can delegate to
    ├── test-writer.md
    ├── adr-author.md
    └── stack-policy-reviewer.md
```

## Hooks

Configured in `settings.json`. They run automatically:

| Event | Hook | What it does |
|---|---|---|
| PreToolUse (Edit/Write) | `pre-edit-guard.sh` | Block edits to `.env`, signing-key files; warn on `libs.versions.toml` |
| PreToolUse (Bash) | `block-dangerous-bash.sh` | Block `rm -rf /`, `git push --force`, `--enable-preview`, Spring Boot 4 / Spring AI 2 references |
| PostToolUse (Edit/Write) | `post-edit-stack-check.sh` | After editing `build.gradle.kts` or `libs.versions.toml`, fail if it contains `-M`/`-RC`/`-SNAPSHOT` or wrong major versions |
| Stop | `stop-verify.sh` | If there are uncommitted changes, remind about `CLAUDE.md` §5 checklist |

**Requirements:** `bash`, `jq`, `grep`, `git`. Install `jq` if missing.

To temporarily disable all hooks: set `"disableAllHooks": true` in `.claude/settings.local.json` (gitignored). Do not disable them in committed config.

## Skills

Loaded on demand by Claude Code when their description matches the current task. They don't bloat the context window unless relevant.

- **`spring-ai-1.1-gotchas`** — patterns that diverge from Spring AI 2.0 docs you might find via web search. Load when working with `ChatClient`, MCP, advisors, or observability.
- **`saga-states`** — the 9 Saga states from `FR-O-001`, which transitions compensate which, idempotency keys, terminal Kafka events. Load when touching the orchestrator.

Add more as you discover recurring gotchas. One skill per topic, ≤ 200 lines each.

## Subagents

Specialised agents the main session can delegate to with the `Task` tool. Each has its own context window — useful for token efficiency on focused tasks.

- **`test-writer`** — write a single failing test from one acceptance criterion. Test-first discipline.
- **`adr-author`** — draft or revise one ADR in the standard 5-section template, 250-400 words.
- **`stack-policy-reviewer`** — runs the §4 / §9 checks against the current diff. Invoke before any commit.

Invocation example from the main agent:
> *"Use the stack-policy-reviewer agent to verify this diff before I commit."*

## Bootstrap

After cloning:

```bash
# Make hook scripts executable
chmod +x .claude/hooks/*.sh

# Verify jq is available
command -v jq || sudo apt-get install -y jq

# Inside Claude Code, list configured hooks
/hooks
```

## Maintenance rule

If Claude Code repeatedly makes the same mistake despite a rule in `CLAUDE.md`, the right escalation is **a hook**, not making `CLAUDE.md` longer. Bloated `CLAUDE.md` causes Claude to ignore rules. Hooks are deterministic.

If Claude Code keeps re-deriving the same domain knowledge in different sessions, the right move is **a skill**, not adding it to `CLAUDE.md`.
