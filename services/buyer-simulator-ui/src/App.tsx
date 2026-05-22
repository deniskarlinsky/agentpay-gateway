import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { toast } from 'sonner'
import { ApiError, postIntentToken, postPayment } from '@/api/agentpay'
import { canonicalForm } from '@/lib/canonicalForm'
import {
  DEFAULT_AGENT_ID,
  DEFAULT_AMOUNT_CAP_PLAIN,
  DEFAULT_AMOUNT_PLAIN,
  DEFAULT_CURRENCY,
  DEFAULT_DESCRIPTION,
  DEFAULT_INTENT_TTL_SECONDS,
  DEFAULT_MERCHANT_ID,
  SCENARIOS,
  type ScenarioFlag,
} from '@/lib/scenarios'
import { isTerminal } from '@/lib/sagaStates'
import { useAgentKey } from '@/lib/useAgentKey'
import { usePollingCase } from '@/lib/usePollingCase'
import { revealStyle } from '@/lib/useStaggeredReveal'
import { DecisionPlane } from '@/components/DecisionPlane'
import { ErrorBanner } from '@/components/ErrorBanner'
import { IntentTicket } from '@/components/IntentTicket'
import { Receipt } from '@/components/Receipt'
import { SagaStateMachine } from '@/components/SagaStateMachine'
import { ScenarioChips } from '@/components/ScenarioChips'

/**
 * Discriminated-union composition root for the buyer simulator.
 *
 * Mode lifecycle:
 *   idle → submitting → polling → terminal | timedOut
 *                                ↘ error  (resettable to idle)
 *
 * Form state (scenario, amount) lives here independently of {@link SimMode}.
 * Selecting a chip while in terminal/timedOut/error auto-resets to idle —
 * matches the "Run again" affordance described in the prompt §2.
 */

type SimMode =
  | { kind: 'idle' }
  | { kind: 'submitting' }
  | { kind: 'polling'; caseId: string; startedAt: number }
  | { kind: 'terminal'; caseId: string; startedAt: number }
  | { kind: 'timedOut'; caseId: string; startedAt: number; lastSeenState: string }
  | { kind: 'error'; message: string; retry: () => void }

function App() {
  const agentKey = useAgentKey()
  const [scenarioFlag, setScenarioFlag] = useState<ScenarioFlag>('happy')
  const [mode, setMode] = useState<SimMode>({ kind: 'idle' })
  const scenario = useMemo(
    () => SCENARIOS.find((s) => s.flag === scenarioFlag) ?? SCENARIOS[0],
    [scenarioFlag],
  )

  // Stable callback for the polling hook's timeout handler — avoids resetting
  // the deadline when the parent re-renders.
  const onTimeoutRef = useRef<(lastSeenState: string | undefined) => void>(
    () => {},
  )
  onTimeoutRef.current = (lastSeenState) => {
    setMode((current) => {
      if (current.kind !== 'polling') return current
      return {
        kind: 'timedOut',
        caseId: current.caseId,
        startedAt: current.startedAt,
        lastSeenState: lastSeenState ?? 'unknown',
      }
    })
  }

  const polling = mode.kind === 'polling'
  const pollingCaseId =
    mode.kind === 'polling' || mode.kind === 'terminal' || mode.kind === 'timedOut'
      ? mode.caseId
      : null
  const pollingStartedAt =
    mode.kind === 'polling' || mode.kind === 'terminal' || mode.kind === 'timedOut'
      ? mode.startedAt
      : null

  const { caseQuery, transitionsQuery } = usePollingCase({
    caseId: pollingCaseId,
    startedAt: pollingStartedAt,
    enabled: polling,
    onTimeout: (lastSeen) => onTimeoutRef.current(lastSeen),
  })

  // Promote the polling mode to terminal once we observe a terminal state.
  // useEffect rather than render-time setState so React 18 strict-mode does
  // not flag a "cannot update state during render" warning.
  useEffect(() => {
    if (
      mode.kind === 'polling' &&
      caseQuery.data &&
      isTerminal(caseQuery.data.state)
    ) {
      setMode({
        kind: 'terminal',
        caseId: mode.caseId,
        startedAt: mode.startedAt,
      })
    }
  }, [mode, caseQuery.data])

  // Surface transient polling errors as Sonner toasts. Same reason as above:
  // toast() is a side effect, not safe at render time under strict mode.
  useEffect(() => {
    if (mode.kind === 'polling' && caseQuery.isError) {
      showCaseErrorOnce(caseQuery.error)
    }
  }, [mode.kind, caseQuery.isError, caseQuery.error])

  const submitDisabled =
    !agentKey.ready ||
    mode.kind === 'submitting' ||
    mode.kind === 'polling'

  const runSimulation = useCallback(async () => {
    if (!agentKey.ready || !agentKey.publicKeyPem) return
    setMode({ kind: 'submitting' })
    const caseId = `case-${crypto.randomUUID().slice(0, 8)}`
    const startedAt = Date.now()
    try {
      const token = await postIntentToken({
        agentId: DEFAULT_AGENT_ID,
        agentPubkey: agentKey.publicKeyPem,
        merchantId: DEFAULT_MERCHANT_ID,
        amountCap: DEFAULT_AMOUNT_CAP_PLAIN,
        currency: DEFAULT_CURRENCY,
        scope: `purchase:${scenario.flag}`,
        ttlSeconds: DEFAULT_INTENT_TTL_SECONDS,
      })

      const canonical = canonicalForm(
        DEFAULT_MERCHANT_ID,
        DEFAULT_AMOUNT_PLAIN,
        DEFAULT_CURRENCY,
        caseId,
      )
      const signature = await agentKey.sign(canonical)

      const payment = await postPayment(token.intentToken, {
        caseId,
        merchantId: DEFAULT_MERCHANT_ID,
        amount: DEFAULT_AMOUNT_PLAIN,
        currency: DEFAULT_CURRENCY,
        description: `${DEFAULT_DESCRIPTION} (scenario ${scenario.flag})`,
        buyerSignature: signature,
        agentMetadata: scenario.agentMetadata,
      })

      setMode({
        kind: 'polling',
        caseId: payment.caseId,
        startedAt,
      })
    } catch (err) {
      const message = formatError(err)
      setMode({
        kind: 'error',
        message,
        retry: () => {
          setMode({ kind: 'idle' })
        },
      })
    }
  }, [agentKey, scenario])

  const handleChipSelect = (flag: ScenarioFlag) => {
    setScenarioFlag(flag)
    setMode((current) =>
      current.kind === 'terminal' ||
      current.kind === 'timedOut' ||
      current.kind === 'error'
        ? { kind: 'idle' }
        : current,
    )
  }

  const handleRunAgain = () => {
    setMode({ kind: 'idle' })
    // Scroll the IntentTicket back into view per the prompt §2 "Run again".
    requestAnimationFrame(() => {
      document
        .getElementById('intent-ticket-anchor')
        ?.scrollIntoView({ block: 'start', behavior: 'auto' })
    })
  }

  const currentState =
    mode.kind === 'polling' || mode.kind === 'terminal' || mode.kind === 'timedOut'
      ? caseQuery.data?.state ?? null
      : null

  const finalised = mode.kind === 'terminal' || mode.kind === 'timedOut'

  // Elapsed seconds while polling — bumped on each render via the polling
  // interval; once terminal, frozen to the last polled value.
  const elapsedSeconds =
    pollingStartedAt !== null && (polling || finalised)
      ? (Date.now() - pollingStartedAt) / 1000
      : null

  const verdicts = transitionsQuery.data?.verdicts ?? []
  const transitions = transitionsQuery.data?.transitions ?? []
  const degraded =
    polling &&
    transitionsQuery.isError &&
    transitionsQuery.error instanceof ApiError &&
    transitionsQuery.error.status === 404

  const warning =
    mode.kind === 'timedOut' ? { lastSeenState: mode.lastSeenState } : null

  return (
    <main className="relative z-10 mx-auto flex min-h-screen max-w-6xl flex-col gap-apg-7 px-apg-5 py-apg-7 text-apg-bone">
      <header
        className="apg-reveal flex items-center justify-between border-b border-apg-rule pb-apg-3"
        style={revealStyle('header')}
      >
        <h1 className="font-serif text-3xl leading-none tracking-tight md:text-4xl">
          AgentPay
        </h1>
        <p className="font-mono text-[0.7rem] uppercase tracking-[0.18em] text-apg-ash">
          buyer simulator
        </p>
      </header>

      {mode.kind === 'error' && (
        <ErrorBanner
          title="Could not submit payment"
          message={mode.message}
          onRetry={mode.retry}
        />
      )}

      <section
        id="intent-ticket-anchor"
        className="apg-reveal grid grid-cols-1 gap-apg-5 md:grid-cols-2"
        style={revealStyle('intent')}
      >
        <IntentTicket
          scenario={scenario}
          onSubmit={runSimulation}
          submitting={mode.kind === 'submitting'}
          submitDisabled={submitDisabled}
          keyReady={agentKey.ready}
        />
        <ScenarioChips
          selected={scenarioFlag}
          onSelect={handleChipSelect}
          disabled={mode.kind === 'submitting' || mode.kind === 'polling'}
        />
      </section>

      <div
        className="apg-reveal border-t border-apg-rule pt-apg-5"
        style={revealStyle('saga')}
      >
        <SagaStateMachine
          currentState={currentState}
          elapsedSeconds={elapsedSeconds}
        />
      </div>

      <div className="apg-reveal" style={revealStyle('decisionPlane')}>
        <DecisionPlane verdicts={verdicts} degraded={!!degraded} />
      </div>

      <Receipt
        status={caseQuery.data ?? null}
        transitions={transitions}
        verdicts={verdicts}
        startedAt={pollingStartedAt}
        finalised={finalised}
        warning={warning}
        onRunAgain={handleRunAgain}
      />
    </main>
  )
}

// Dedupe transient polling toasts — React Query may surface the same error
// across multiple renders before the next refetch. We key by error message;
// any structurally different error breaks through.
let lastToastedError: string | null = null
function showCaseErrorOnce(err: unknown) {
  const message = formatError(err)
  if (lastToastedError === message) return
  lastToastedError = message
  toast.error('polling case failed', { description: message })
  // Clear after 5s so the same transient class can re-toast on a fresh
  // disconnect later in the same session.
  window.setTimeout(() => {
    if (lastToastedError === message) lastToastedError = null
  }, 5000)
}

function formatError(err: unknown): string {
  if (err instanceof ApiError) {
    return `${err.message}${typeof err.body === 'string' ? `: ${err.body}` : ''}`
  }
  if (err instanceof Error) return err.message
  return 'unknown error'
}

export default App
