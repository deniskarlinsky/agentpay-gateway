# buyer-simulator-ui

Browser-side dramatisation of a single agent-initiated purchase against the
AgentPay gateway. Mints a session keypair in JavaScript memory, calls
`/intent-tokens` → `/payments` → polls `/cases/{id}` and `/cases/{id}/transitions`,
and renders the saga state machine + decision-plane fan-out in real time.
This module is a sibling of `services/buyer-client/`, which does the same flow
from a Java CLI.

Visual spec and aesthetic direction live in
[`docs/buyer-simulator-ux.md`](../../docs/buyer-simulator-ux.md). Read that
first — the palette, typography and motion rules in §4 are the source of truth
for every component in `src/components/`.

## Stack

- Vite 8 + React 19 + TypeScript (strict).
- Tailwind CSS 3.x with the APG palette and spacing scale wired into
  `tailwind.config.ts`.
- shadcn/ui (`new-york` style) — only the components listed in the UX doc §4
  are vendored under `src/components/ui/`.
- TanStack Query as the data-fetching layer (installed; wiring lives in Task 3).
- Sonner for transient error toasts.
- lucide-react for iconography.

## Develop

```bash
npm install
npm run dev      # vite, http://localhost:5173
npm run build    # tsc -b && vite build, emits dist/
npm run lint     # eslint
npm run format   # prettier --write
```

The dev server proxies nothing — the page hits the gateway directly at
`VITE_GATEWAY_BASE_URL`. Copy `.env.example` to `.env.local` and edit if your
gateway is not on `http://localhost:8080`.

## Environment

| Variable                | Default                 | Used in      |
| ----------------------- | ----------------------- | ------------ |
| `VITE_GATEWAY_BASE_URL` | `http://localhost:8080` | `src/env.ts` |

The default mirrors `services/buyer-client/src/main/resources/application.yml`,
so `make up` followed by `npm run dev` works with zero configuration.

## Deploy

Static-only — `npm run build` writes a self-contained bundle to `dist/`. Task 5
wires an nginx container around that directory and adds it to
`docker-compose.yml`. There is no SSR step.

## What's in scope vs. what isn't

This directory is the **scaffold only**. The components described in the UX
doc (`Ticket`, `ScenarioChip`, `SagaStateMachine`, `FanOutTriangle`,
`SpecialistCard`, `Receipt`) and the `src/api/agentpay.ts` client are
implemented in Task 3. `App.tsx` currently renders a placeholder so reviewers
can eyeball the theme and font stack before any real UI lands.
