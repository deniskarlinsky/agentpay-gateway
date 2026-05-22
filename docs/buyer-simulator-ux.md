# Buyer Simulator — UX Concept

> Source-of-truth for the `services/buyer-simulator-ui/` module. Read this before scaffolding the
> frontend or adding backend endpoints to support it. Companion to `REQUIREMENTS.md` §10 and
> `docs/architecture.md`.

## 0. Aesthetic direction

> Applied per the frontend-design skill: pick a bold, specific aesthetic; avoid generic AI-dashboard
> defaults (no Inter, no Space Grotesk, no flat slate-950-with-emerald, no symmetric three-card
> grid, no purple gradients).

**Direction: "Precision broadsheet."** Bloomberg terminal crossed with an editorial newspaper. The
visual language of an instrument that prints a transaction receipt — precise, hairline rules,
bone-white text on warm-black paper, a single brass accent for emphasis. The saga state machine is
the *hero* artifact; the rest of the page frames it with newsprint deference. No rounded soft cards.
No gradients-on-white. No emoji.

**Reference quality bar:** Linear (the marketing site, not the app), Vercel.com, the Financial Times
data graphics page, Stripe Sessions stage UI. We are not cloning any of these — we are reaching for
their commitment to a clear point-of-view.

**The "remember-it" detail:** brass-on-bone-on-warm-black with hairline 1px rules instead of cards.
No rounded chrome, no drop shadow, no gradients-on-white. Headlines in Instrument Serif against
mono labels — the inverted weight pair you don't see on AI dashboards. Scenario chips numbered
**I. II. III.** in serif italic like footnote markers. Amounts hairline-underlined to render as
load-bearing data, not decoration.

## 1. Story

**Scenario.** An AI shopping assistant (`shop-bot`) is buying a SKU on behalf of a human (Alice).
Alice has authorised her agent for one purchase up to $50 at `merchant-acme`. The simulator
dramatises what AgentPay does between the agent clicking "buy" and the funds being captured — the
intent-token issuance, the parallel risk/compliance/routing fan-out, the Saga state machine, the
PSP routing.

**Why this narrative beat the other three.** The "authorise my agent for one purchase up to $X"
framing is the clearest single-sentence pitch of *scoped JWT intent tokens* — the architectural
feature most unique to AgentPay. The other candidates (B2B invoice, AI travel agent, SaaS
subscription) either move the camera away from intent scoping (subscription = recurring) or
require richer mock data (flight = PNRs, invoice = line items) that bloats the simulator without
strengthening the value proposition. Coffee-for-Alice maps onto the existing `Scenario.HAPPY`
fixture (Alice Buyer → merchant-acme, $42.50, SKU-42) — no fixture work, no merchant-onboarding
fiction, no fresh prompts.

**Screencast hook (≤ 8 seconds).** "Your AI agent just made a payment. Here's the 7-step decision
your gateway ran in the 1.8 seconds you weren't looking."

## 2. Screens

One page. **Asymmetric** — not a three-card symmetric grid. Designed for 1280px, responsive down
to 375px (zones stack vertically; the fan-out triangle linearises). Dark mode default.

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│  AgentPay  ───────────────────────────────────────────────  buyer simulator      │  ← hairline rule
│                                                                                  │
│                                                                                  │
│  ┌──── INTENT TICKET ─────────────┐         I. Happy path                        │
│  │                                │              expected: COMMITTED             │
│  │  buyer agent                   │                                              │
│  │    shop-bot                    │         II. Compliance fail                  │
│  │    on behalf of Alice Buyer    │              expected: DECLINED              │
│  │                                │                                              │
│  │  merchant                      │         III. High-risk review                │
│  │    merchant-acme               │              expected: SUSPENDED_FOR_REVIEW  │
│  │    "Acme Widgets Ltd"          │                                              │
│  │                                │              ┌────────────────────────┐     │
│  │  amount                        │              │  Run AgentPay simulation │   │
│  │    $42.50 ──── cap $50.00      │              └────────────────────────┘     │
│  │                                │                                              │
│  │  description                   │                                              │
│  │    SKU-42 widget               │                                              │
│  └────────────────────────────────┘                                              │
│                                                                                  │
│  ─────────────────────────────────────────────────────────────────────────────   │  ← hairline
│                                                                                  │
│  SAGA                                                              1.84s elapsed │
│                                                                                  │
│    ╔═════════╗   ╔═════════╗   ╔═════════╗   ╔═════════╗   ╔═════════╗  ●        │
│    ║INITIATED║──>║  HELD   ║──>║REVIEWING║──>║APPROVED ║──>║ ROUTED  ║──>COMMITTED│
│    ╚═════════╝   ╚═════════╝   ╚═════════╝   ╚═════════╝   ╚═════════╝           │
│         ✓             ✓             ✓             ✓             ✓     (current)  │
│                                                                                  │
│                  └────> DECLINED               └────> COMPENSATED                │
│                  └────> SUSPENDED_FOR_REVIEW                                     │
│                                                                                  │
│                                                                                  │
│  DECISION PLANE     supervisor → fan-out → 3 specialists in parallel             │
│                                                                                  │
│   ┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐      │
│   │  Risk               │  │  Compliance         │  │  Routing            │      │
│   │  ── sonnet-4-6      │  │  ── sonnet-4-6      │  │  ── haiku-4-5       │      │
│   │                     │  │                     │  │                     │      │
│   │   8 / 100           │  │   PASS              │  │   psp-c             │      │
│   │   CLEAR             │  │   no SDN match      │  │   98% · 45 bps      │      │
│   │                     │  │                     │  │                     │      │
│   │   1.21s · $0.0024   │  │   1.40s · $0.0031   │  │   0.61s · $0.0008   │      │
│   │   ▸ rationale       │  │   ▸ rationale       │  │   ▸ rationale       │      │
│   └─────────────────────┘  └─────────────────────┘  └─────────────────────┘      │
│                                                                                  │
│  ─────────────────────────────────────────────────────────────────────────────   │  ← hairline
│                                                                                  │
│  RECEIPT                                                                         │
│                                                                                  │
│    ✓ COMMITTED   ───   $42.50 charged via psp-c   ───   auth AUTH-93812          │
│                                                                                  │
│    case_id          case-7f2a91c0                                                │
│    total elapsed    1.84s                                                        │
│    total cost       $0.0063                                                      │
│                                                                                  │
│    ↗ langfuse trace      ▾ raw decision JSON      ▾ saga history                 │
└──────────────────────────────────────────────────────────────────────────────────┘
```

**Panels explained.**

- **Header.** No logo bling. Wordmark "AgentPay" in Instrument Serif, a hairline rule across the
  page, "buyer simulator" in small Geist sans on the right. Reads like a section header in a
  print broadsheet.

- **Intent ticket.** Bordered by hairline 1px rules on top, bottom, left, right. Title row in
  Geist Mono small-caps "INTENT TICKET". Labels in Geist Mono uppercase, values in Geist sans —
  the inverted-weight pattern is intentional and characterful. Amount value sits next to a hairline
  underline that fills to the right to show the cap headroom (`$42.50 ──── cap $50.00`). Treat
  this panel as if it were an actual stub a clerk might tear off — narrow column, generous interior
  whitespace, no rounded corners.

- **Scenario chips.** Right-hand column, NOT tabs. Three vertically-stacked items with Roman
  numerals in Instrument Serif italic (I., II., III.) — a deliberate editorial flourish. Each chip
  shows its name in Geist sans and the expected terminal state in Geist Mono small. The selected
  chip gets a brass left-rule (3px). No card chrome, no hover-state pill.

- **Saga state machine (hero).** Full-width centerpiece below the ticket. The 9 saga states render
  as monospace-labelled boxes connected by hand-drawn-feel arrows (SVG paths with subtle 1px
  stroke). Traversed states show a tiny `✓` glyph below. Current state pulses with a brass
  radial-glow halo (4% intensity). Future states render at 35% opacity. Two branch labels float
  below the main path — `└────> DECLINED`, `└────> SUSPENDED_FOR_REVIEW`, `└────> COMPENSATED`
  — they brighten only if the case enters them. A subtle scan-line pulse runs over the whole
  diagram **only while the case is in-flight**, and stops cleanly on terminal.

- **Decision plane (horizontal row).** A single Geist Mono small-caps heading
  `DECISION PLANE` with a one-line subhead `supervisor → fan-out → 3 specialists in parallel`,
  then three specialist panels side-by-side in fixed order Risk → Compliance → Routing. Each
  panel shares the same hairline-rule treatment as the ticket — NO rounded corners, NO drop
  shadow. Five content rows inside each panel:
    1. Title in Geist sans, model ID in Geist Mono small underneath
    2. Primary verdict, large, Instrument Serif numeral or 4-letter caps (`PASS` / `CLEAR`)
    3. Sub-verdict, smaller, Geist Mono
    4. Latency · cost in Geist Mono
    5. Expandable rationale on `▸` click — `Collapsible`
  Panels fill **in parallel** as each verdict arrives — content swaps from skeleton to real
  value in place; no entrance animation. The subhead line carries the pedagogical message that
  this is fan-out, not a pipeline. Below 768px viewport width the row collapses to a vertical
  stack, preserving the Risk → Compliance → Routing order.

- **Receipt.** Footer treatment. Hairline rules above and below. Terminal state in Instrument Serif
  display (`COMMITTED` / `DECLINED` / `SUSPENDED_FOR_REVIEW`), 32px, brass-on-bone OR
  semantic-colored. Below: three columns of `case_id` / `total elapsed` / `total cost` in
  Geist Mono. At the bottom, three understated links with `▾` / `↗` glyphs — no buttons, no
  pills, just text.

**Empty / loading / error states.**

- **Empty (pre-submission).** Saga state machine renders all 9 boxes at 35% opacity. Specialist
  cards render as ghost-cards (hairline outlines only, no content). Receipt area shows
  `awaiting submission` in Geist Mono italic.
- **Loading.** State machine bubbles fill one by one as transitions arrive. Specialist cards
  render `Skeleton`s with the model ID and title visible but verdict cells dim. The scan-line
  ambient pulse runs over the saga only.
- **Error.** Sonner toast for transient polling errors (1Hz polling — easy to recover). A
  persistent `Alert destructive` in brass-on-rose for hard failures (gateway 4xx) with a
  text-only "↻ retry" link that resets state.
- **Polling timeout (>60s).** Warning alert "Case did not finalise within 60s — last seen
  `<state>`. Check orchestrator logs." with link to the orchestrator container.

## 3. Scenario variants (three Roman-numeralled chips)

| | Pre-baked values | Expected terminal | Why it works |
|---|---|---|---|
| **I. Happy path** | Alice Buyer, US merchant, $42.50, agent metadata from `Scenario.HAPPY` | `COMMITTED` | Demonstrates the 95% case — fast, clean, all three specialist cards green. |
| **II. Compliance fail** | "Fictitious Bad Actor" (SDN fixture entry), $42.50, agent metadata from `Scenario.COMPLIANCE_FAIL` | `DECLINED` | Sanctions MCP fires the rejection path — viewers see the Compliance card flip to red with the synthetic SDN citation. |
| **III. High-risk review** | "Risky Reviewer" (cross-border, large amount, recent-first-seen), agent metadata from `Scenario.REVIEW` | `SUSPENDED_FOR_REVIEW` | Risk score lands in 50–79 band; saga halts; viewers see the explicit human-approval gate. Best-effort against the live model — same caveat as the existing CLI behavior. |

Scenario fixtures **must** mirror
`services/buyer-client/src/main/java/com/agentpay/buyer/scenarios/Scenario.java` exactly — same
`agent_metadata` map, same buyer names. The UI ships those values as a TypeScript constant; if the
Java enum changes, this constant must change too. (A future enhancement could expose the fixtures
as a gateway endpoint — flagged in `docs/known-issues.md` if drift becomes a problem.)

## 4. Visual style — design tokens

Encoded as CSS variables (`--apg-*`) and Tailwind theme extensions. The shadcn default theme is
**replaced** wholesale, not just tinted.

**Palette.**

| Role | Token | Hex | Use |
|---|---|---|---|
| Page background | `--apg-ink` | `#0A0A0B` | Warm near-black; NOT slate-950 |
| Surface | `--apg-surface` | `#131316` | Cards, ticket interior |
| Surface raised | `--apg-surface-2` | `#1B1B1F` | Hover, scenario chip selected |
| Hairline | `--apg-rule` | `#26262B` | All 1px borders, separators, dividers |
| Text primary | `--apg-bone` | `#F4F4F2` | Bone white, slightly warm |
| Text secondary | `--apg-ash` | `#7C7C82` | Labels, secondary copy |
| Accent (brass) | `--apg-brass` | `#E8C547` | Selection rules, hovered links, current-state halo |
| State success | `--apg-emerald` | `#5DD891` | COMMITTED / PASS / CLEAR only |
| State review | `--apg-amber` | `#F5C156` | SUSPENDED_FOR_REVIEW / 50-79 risk only |
| State fail | `--apg-rose` | `#FF6B7A` | DECLINED / FAIL / COMPENSATED only |

Discipline: brass is for **chrome / focus / emphasis**. Semantic colors are for **state truth**. They
never overlap. The brass accent on a button is fine; using brass to indicate "approved" is not.

**Typography.**

| Role | Family | Source | Use |
|---|---|---|---|
| Display | **Instrument Serif** | Google Fonts (free, OFL) | Headings, terminal-state callouts, Roman numerals |
| UI sans | **Geist** | Vercel (free, OFL) | Body, form labels, buttons |
| Mono | **Geist Mono** | Vercel (free, OFL) | Case IDs, amounts, JSON, model IDs, latency · cost |

Numerals everywhere: `font-variant-numeric: tabular-nums lining-nums`. Amounts get a hairline
underline (1px brass, 30% opacity) — a tiny editorial flourish that reads as "this number is
load-bearing."

**Spacing scale (rems).** `0.25 · 0.5 · 0.75 · 1 · 1.5 · 2 · 3 · 5 · 8` — wider top jumps than the
default 4-8-16-32-64 to enable editorial whitespace at the macro level while keeping micro spacing
tight.

**Radii.** `0` on hero structural panels (ticket, state-machine boxes, specialist cards, receipt).
`4px` only on interactive controls (buttons, scenario-chip selected ring, focus rings).

**Atmosphere.** NOT flat. Two layers:
1. SVG noise overlay (`feTurbulence` filter, 1.5% opacity, `mix-blend-mode: overlay`) over the full
   viewport — gives newsprint tactility.
2. A single 4% radial gradient tinted brass, anchored behind the saga state machine center,
   fading to ink at the edges — implies "this is where attention belongs."

**Motion.** Rule: motion communicates state change, never entertains. If an animation carries no
information, it is not in the build.

- **Page load.** Staggered fade-up. Header (60ms) → ticket + scenario chips (140ms) → saga
  machine (220ms) → decision plane row (320ms). 300ms ease-out-expo each, 24px translateY.
- **State transition.** Saga state machine first bubble lights instantly on submit. Subsequent
  transitions arrive at whatever cadence the orchestrator delivers them — each animated as an
  80ms ease-out-expo "stroke trace" along the connector from the previous bubble. This is the
  only ongoing animation; it is communicating an actual state change.
- **Verdicts land.** Skeleton row of each specialist panel is replaced in place with real content
  the instant its `agent_verdicts` row arrives. No entrance animation. The fact of the swap is
  the signal.
- **Hovers / focus.** A thin brass underline animates in (200ms ease-out) under interactive text.
  No background fills, no scale transforms. (Underline = "this is interactive" — also a state
  change signal.)

Explicitly **out**: no scan-line ambient pulse, no card flip / "deal" animation, no
loading-spinner-as-decoration anywhere.

**Iconography.** lucide-react. Geometric, line-based. Never decorative. Sized to match the
surrounding type's cap height. No emoji.

**shadcn/ui components to init** (theme stripped and replaced): `button`, `card` (used minimally —
the ticket, specialist cards, and receipt are custom), `input`, `label`, `badge`, `radio-group`,
`skeleton`, `collapsible`, `tooltip`, `separator`, `alert`, `sonner`.

**Custom components**: `Ticket`, `ScenarioChip`, `SagaStateMachine`, `FanOutTriangle`,
`SpecialistCard`, `Receipt`.

## 5. Backend changes

The existing `GET /cases/{case_id}` (REQUIREMENTS §7.1.3) returns `state`, `decision`,
`psp_outcome`, `trace_url` — enough for the final receipt but **not** enough for the saga state
machine animation or the per-agent card animation. We need richer history.

**New endpoint:** `GET /cases/{case_id}/transitions`. Polled at 1 Hz alongside the existing
`/cases/{case_id}`. Returns the saga transitions and per-agent verdicts:

```json
{
  "case_id": "case-7f2a91c0",
  "current_state": "ROUTED",
  "transitions": [
    {"state_from": null,         "state_to": "INITIATED", "reason": "intent token validated", "created_at": "2026-05-22T10:00:00.123Z"},
    {"state_from": "INITIATED",  "state_to": "HELD",      "reason": "funds held",              "created_at": "2026-05-22T10:00:00.187Z"},
    {"state_from": "HELD",       "state_to": "REVIEWING", "reason": "decision plane invoked",  "created_at": "2026-05-22T10:00:00.201Z"},
    {"state_from": "REVIEWING",  "state_to": "APPROVED",  "reason": "supervisor verdict",      "created_at": "2026-05-22T10:00:01.523Z"},
    {"state_from": "APPROVED",   "state_to": "ROUTED",    "reason": "psp-c selected",          "created_at": "2026-05-22T10:00:01.612Z"}
  ],
  "verdicts": [
    {"agent_name": "RiskAgent",       "model": "claude-sonnet-4-6", "verdict_json": {"score": 8, "signals": [], "rationale": "..."},                                                                              "latency_ms": 1234, "cost_usd": 0.0024, "created_at": "2026-05-22T10:00:01.421Z"},
    {"agent_name": "ComplianceAgent", "model": "claude-sonnet-4-6", "verdict_json": {"outcome": "PASS", "citations": [], "rationale": "..."},                                                                    "latency_ms": 1402, "cost_usd": 0.0031, "created_at": "2026-05-22T10:00:01.589Z"},
    {"agent_name": "RoutingAgent",    "model": "claude-haiku-4-5",  "verdict_json": {"psp_id": "psp-c", "route_id": "...", "expected_success_rate": 0.98, "expected_cost_bps": 45, "rationale": "..."}, "latency_ms": 612,  "cost_usd": 0.0008, "created_at": "2026-05-22T10:00:01.310Z"}
  ]
}
```

**Why a new endpoint, not SSE / WebSocket.** Polling at 1 Hz is sufficient — saga transitions take
hundreds of milliseconds, not single-digit milliseconds. SSE would require an event-bus or
long-polling adapter; WebSocket would add a second authentication surface. Neither pulls its
weight for a demo UI. Stop polling once the case enters a terminal state (`COMMITTED` |
`DECLINED` | `COMPENSATED` | `SUSPENDED_FOR_REVIEW`).

**Why both `/cases/{id}` and `/cases/{id}/transitions`.** The existing endpoint already encodes the
public contract in REQUIREMENTS §7.1.3 — touching its shape risks coupling the demo UI to a
protocol concern. The transitions endpoint is purely informational; it can evolve freely without
breaking the buyer-client.

**Implementation location.** Gateway proxies `GET /cases/{case_id}/transitions` to
`GET /internal/cases/{case_id}/transitions` on the orchestrator, following the existing pattern in
`CaseStatusController` → `OrchestratorClient`. Orchestrator-side new controller queries
`saga_transitions` and `agent_verdicts` tables (both already populated by the saga; no schema
changes). Both endpoints are unauthenticated to match the existing `/cases/{id}` choice (see the
javadoc on `CaseStatusController`).

**Signing.** The simulator must produce a valid `buyer_signature` for `POST /payments`. It uses
the Web Crypto API (`crypto.subtle.generateKey` + `crypto.subtle.sign`) to mint a fresh RSA-2048
keypair on page load, exports the public key as SPKI/PEM for `POST /intent-tokens`, and signs the
canonical form `merchant_id|amount|currency|case_id` (UTF-8 bytes) with
`RSASSA-PKCS1-v1_5 / SHA-256`. Matches the existing buyer-client's scheme exactly (see
`services/buyer-client/.../keys/CanonicalForm.java`). The gateway has a TODO to verify the
signature (`PaymentService.java`); the UI sends a valid signature regardless so it stays correct
once verification is wired up.

**Key lifecycle — non-negotiable.** The keypair lives only in JavaScript memory for the lifetime
of the page. It MUST NOT be written to `localStorage`, `sessionStorage`, IndexedDB, cookies, or
any other persistence surface. Reloading the page mints a new keypair. The `CryptoKey` objects
are generated with `extractable: false` for the private key.

**Honesty about what this is.** The intent ticket carries a small Geist Mono italic note
underneath the form, near the buyer-agent label: `simulating what a real AI agent would do — this
demo mints a session keypair in your browser; a production agent ships with its own signing key`.
That line is part of the design, not an afterthought. The UI is a viewer for the AgentPay
backend — it is not a key-management surface, and it should not pretend to be one.

## 6. Out of scope (for this UX iteration)

- No login / accounts. Anonymous.
- No history of past simulations.
- No editable scenario fixtures.
- No multi-language.
- No A2A `AgentCard` rendering.
- No real-time per-case cost meter against the NFR-COST-001 budget — final cost only.
- No Grafana embed.

## 7. Open questions / future enhancements

- Expose scenario fixtures as a gateway endpoint so the UI stays in sync with the Java enum
  automatically. Today they are duplicated.
- Optional: a fourth "IV. Custom" scenario chip that lets the viewer edit `agent_metadata`
  free-form — useful for exploring how the prompts react to different inputs. Out of MVP scope.
- Optional: surface the W3C trace context header (`traceparent`) on every panel so a viewer can
  cross-reference into Langfuse without leaving the page.
