# Contributing to AgentPay Gateway

AgentPay Gateway is a reference architecture, not an active product. Pull requests are welcome but expect slow review — this is a maintenance-light project.

## Before you write code

Open an issue first. Scope is deliberately bounded by [`REQUIREMENTS.md`](REQUIREMENTS.md) — every feature has an ID (`FR-G-001`, `NFR-S-007`) and acceptance criteria. If you want to add something not listed there, open an issue using the **scope proposal** template so we can decide whether it fits the project's narrow scope before you invest time.

## Stack policy is non-negotiable

This repo enforces a deliberately narrow stack: Java 21 LTS (no preview features), Spring Boot 3.5.x, Spring AI 1.1.5, pinned via `gradle/libs.versions.toml`. The full policy and rationale lives in [`CLAUDE.md`](CLAUDE.md) §4. PRs that bump to milestone, RC, or SNAPSHOT versions, introduce Lombok, switch to reactive Spring, or add Claude Opus models will be closed.

## Build, test, format

See [`CLAUDE.md`](CLAUDE.md) §5 (pre-commit checklist) and §11 (common commands). Quick reference:

```bash
./gradlew :<module>:test           # affected tests
./gradlew dependencies | grep -E "M[0-9]+$|RC|SNAPSHOT"   # must return nothing
./gradlew spotlessApply            # format
make demo                          # happy path + compensation
```

Every commit must pass §5. Hooks under `.claude/settings.json` enforce this automatically when present.

## Architectural changes

Any architectural decision goes in `docs/adr/` as a new ADR file. Follow the format of [`docs/adr/001-stable-stack-baseline.md`](docs/adr/001-stable-stack-baseline.md): Status / Context / Decision / Consequences / Alternatives considered. Keep each ADR to one page (250–400 words) and cross-link related ADRs.

## Commits and PRs

- One logical change per commit.
- [Conventional Commits](https://www.conventionalcommits.org/): `feat(gateway): issue intent tokens (FR-G-001)`.
- Reference a requirement ID or ADR number in the commit body where applicable.
- PR description should include: the requirement ID or ADR, test evidence (paste output of the affected test target), and a one-line summary of why the change is in scope.

## Reporting issues

Use the issue templates under `.github/ISSUE_TEMPLATE/` — they exist to keep discussions focused.

## Code of Conduct

This project follows the [Contributor Covenant 2.1](CODE_OF_CONDUCT.md). Be respectful.
