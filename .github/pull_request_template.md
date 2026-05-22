**Reference**

<!-- Requirement ID (e.g. `FR-G-001`) or ADR number this PR implements. If neither applies, link the issue that authorised the work. -->

**Summary**

<!-- One paragraph: what changed and why. -->

**Test evidence**

<!-- Paste the relevant test target output. -->

```bash
./gradlew :<module>:test
```

**Pre-commit checklist (see CLAUDE.md §5)**

- [ ] Affected tests pass.
- [ ] `./gradlew dependencies | grep -E "M[0-9]+$|RC|SNAPSHOT"` returns nothing.
- [ ] `./gradlew spotlessApply` clean.
- [ ] Commit message follows Conventional Commits (`feat(scope): ... (FR-NNN-NNN)`).
