# Security Policy

AgentPay Gateway is a reference architecture, not a production service. It is not deployed anywhere and processes no real funds or personal data. Vulnerabilities here are educational; impact is bounded by that context.

## Reporting a vulnerability

Email **denis.karlinsky@gmail.com** with:

- A description of the issue.
- A minimal reproduction (commit SHA, steps, expected vs actual).
- Your assessment of severity and exploitability.

Please do not open a public GitHub issue for suspected vulnerabilities.

## Response expectations

This project is maintenance-light. Reasonable-effort response within two weeks; no SLA. Fixes for valid findings will land as normal PRs with a security-relevant commit message and, where appropriate, a new ADR.

## Bug bounty

There is no bug bounty programme. Reports are appreciated but not compensated.

## Scope

In scope: code in this repository.

Out of scope: third-party dependencies (report upstream), infrastructure not controlled by this repo, social engineering, denial-of-service against demo runs.
