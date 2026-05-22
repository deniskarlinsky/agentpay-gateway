import { useState } from 'react'
import { ChevronDown, ChevronRight, ExternalLink, RotateCcw } from 'lucide-react'
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from '@/components/ui/collapsible'
import type { CaseStatus, Transition, Verdict } from '@/api/agentpay'
import { isTerminal } from '@/lib/sagaStates'
import { cn } from '@/lib/utils'

/**
 * Footer receipt panel (UX doc §2 "Receipt"). Renders terminal state in
 * Instrument Serif display at 32px, semantic-coloured by terminal kind.
 * Below: case_id / total elapsed / total cost in Geist Mono. At the bottom,
 * three understated links: trace, raw decision, saga history. Plus the
 * "↻ run another scenario" affordance that resets {@code mode} to idle.
 */
type Props = {
  status: CaseStatus | null
  transitions: Transition[]
  verdicts: Verdict[]
  startedAt: number | null
  /** True once the case has reached a terminal state (or polling timed out). */
  finalised: boolean
  /** Optional banner above the receipt — used for the >60s timeout warning. */
  warning?: { lastSeenState: string } | null
  onRunAgain: () => void
}

export function Receipt({
  status,
  transitions,
  verdicts,
  startedAt,
  finalised,
  warning,
  onRunAgain,
}: Props) {
  if (!finalised && !status) {
    return <PreSubmissionReceipt />
  }

  const totalElapsedSeconds =
    transitions.length >= 2
      ? secondsBetweenTransitions(transitions)
      : startedAt !== null && status
        ? secondsSince(startedAt)
        : null

  const totalCost = verdicts.reduce((acc, v) => acc + v.costUsd, 0)

  return (
    <section className="flex flex-col gap-apg-4 border-t border-apg-rule pt-apg-5">
      <header>
        <h2 className="font-mono text-[0.7rem] uppercase tracking-[0.18em] text-apg-ash">
          Receipt
        </h2>
      </header>

      {warning && (
        <p
          role="alert"
          className="font-mono text-xs text-apg-amber"
        >
          Case did not finalise within 60s — last seen{' '}
          <span className="text-apg-bone">{warning.lastSeenState}</span>. Check
          orchestrator logs.
        </p>
      )}

      <div className="flex flex-col gap-apg-4 md:flex-row md:items-baseline md:justify-between">
        <TerminalHeadline state={status?.state ?? null} />
        <PspLine status={status} />
      </div>

      <dl className="grid grid-cols-1 gap-apg-3 md:grid-cols-3">
        <Stat label="case_id" value={status?.caseId ?? '—'} />
        <Stat
          label="total elapsed"
          value={
            totalElapsedSeconds !== null
              ? `${totalElapsedSeconds.toFixed(2)}s`
              : '—'
          }
        />
        <Stat
          label="total cost"
          value={verdicts.length > 0 ? `$${totalCost.toFixed(4)}` : '—'}
        />
      </dl>

      <ReceiptLinks
        traceUrl={status?.traceUrl ?? null}
        decision={status?.decision ?? null}
        transitions={transitions}
      />

      <div className="flex justify-end pt-apg-3">
        <button
          type="button"
          onClick={onRunAgain}
          className="apg-link inline-flex items-center gap-apg-2 font-mono text-xs text-apg-ash hover:text-apg-bone focus-visible:outline-none focus-visible:text-apg-bone"
        >
          <RotateCcw className="h-3 w-3" aria-hidden />
          run another scenario
        </button>
      </div>
    </section>
  )
}

function PreSubmissionReceipt() {
  return (
    <section className="flex flex-col gap-apg-3 border-t border-apg-rule pt-apg-5">
      <h2 className="font-mono text-[0.7rem] uppercase tracking-[0.18em] text-apg-ash">
        Receipt
      </h2>
      <p className="font-mono text-xs italic text-apg-ash">
        awaiting submission
      </p>
    </section>
  )
}

function TerminalHeadline({ state }: { state: string | null }) {
  const tone =
    state === 'COMMITTED'
      ? 'text-apg-emerald'
      : state === 'DECLINED' || state === 'COMPENSATED'
        ? 'text-apg-rose'
        : state === 'SUSPENDED_FOR_REVIEW'
          ? 'text-apg-amber'
          : 'text-apg-bone'

  const label = state && isTerminal(state) ? state : (state ?? '—')

  return (
    <h3 className={cn('font-serif text-3xl leading-none md:text-[2rem]', tone)}>
      {state ? (
        <>
          <span aria-hidden className="mr-apg-2">
            {state === 'COMMITTED'
              ? '✓'
              : state === 'DECLINED' || state === 'COMPENSATED'
                ? '✕'
                : state === 'SUSPENDED_FOR_REVIEW'
                  ? '‖'
                  : ''}
          </span>
          {label}
        </>
      ) : (
        '—'
      )}
    </h3>
  )
}

function PspLine({ status }: { status: CaseStatus | null }) {
  if (!status || status.state !== 'COMMITTED' || !status.pspOutcome) {
    return null
  }
  return (
    <p className="font-mono text-xs uppercase tracking-wider text-apg-ash">
      {status.pspOutcome.status.toLowerCase()} via psp
      {status.pspOutcome.reasonCode
        ? ` — ${status.pspOutcome.reasonCode}`
        : ''}
    </p>
  )
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex flex-col gap-apg-1">
      <dt className="font-mono text-[0.65rem] uppercase tracking-[0.18em] text-apg-ash">
        {label}
      </dt>
      <dd className="m-0 font-mono text-sm text-apg-bone">{value}</dd>
    </div>
  )
}

type ReceiptLinksProps = {
  traceUrl: string | null
  decision: Record<string, unknown> | null
  transitions: Transition[]
}

function ReceiptLinks({ traceUrl, decision, transitions }: ReceiptLinksProps) {
  const [openDecision, setOpenDecision] = useState(false)
  const [openHistory, setOpenHistory] = useState(false)
  return (
    <div className="flex flex-col gap-apg-3 pt-apg-2">
      <div className="flex flex-col gap-apg-3 md:flex-row md:gap-apg-5">
        {traceUrl && (
          <a
            href={traceUrl}
            target="_blank"
            rel="noreferrer noopener"
            className="apg-link inline-flex items-center gap-apg-1 font-mono text-xs text-apg-ash hover:text-apg-bone focus-visible:outline-none focus-visible:text-apg-bone"
          >
            <ExternalLink className="h-3 w-3" aria-hidden />
            langfuse trace
          </a>
        )}

        <Collapsible
          open={openDecision}
          onOpenChange={setOpenDecision}
        >
          <CollapsibleTrigger asChild>
            <button
              type="button"
              disabled={!decision}
              className="apg-link inline-flex items-center gap-apg-1 font-mono text-xs text-apg-ash hover:text-apg-bone focus-visible:outline-none focus-visible:text-apg-bone disabled:opacity-50"
            >
              {openDecision ? (
                <ChevronDown className="h-3 w-3" aria-hidden />
              ) : (
                <ChevronRight className="h-3 w-3" aria-hidden />
              )}
              raw decision JSON
            </button>
          </CollapsibleTrigger>
        </Collapsible>

        <Collapsible open={openHistory} onOpenChange={setOpenHistory}>
          <CollapsibleTrigger asChild>
            <button
              type="button"
              disabled={transitions.length === 0}
              className="apg-link inline-flex items-center gap-apg-1 font-mono text-xs text-apg-ash hover:text-apg-bone focus-visible:outline-none focus-visible:text-apg-bone disabled:opacity-50"
            >
              {openHistory ? (
                <ChevronDown className="h-3 w-3" aria-hidden />
              ) : (
                <ChevronRight className="h-3 w-3" aria-hidden />
              )}
              saga history
            </button>
          </CollapsibleTrigger>
        </Collapsible>
      </div>

      <Collapsible open={openDecision}>
        <CollapsibleContent>
          {decision && (
            <pre className="mt-apg-2 max-h-72 overflow-auto border border-apg-rule bg-apg-surface p-apg-4 font-mono text-[0.7rem] text-apg-bone">
              {JSON.stringify(decision, null, 2)}
            </pre>
          )}
        </CollapsibleContent>
      </Collapsible>
      <Collapsible open={openHistory}>
        <CollapsibleContent>
          {transitions.length > 0 && (
            <ol className="mt-apg-2 border border-apg-rule bg-apg-surface px-apg-4 py-apg-3">
              {transitions.map((t, idx) => (
                <li
                  key={`${idx}-${t.stateTo}`}
                  className="flex flex-col gap-apg-1 border-b border-apg-rule py-apg-2 last:border-b-0 md:flex-row md:items-baseline md:justify-between"
                >
                  <div className="flex items-baseline gap-apg-2 font-mono text-xs text-apg-bone">
                    <span className="text-apg-ash">
                      {t.stateFrom ?? 'START'}
                    </span>
                    <span aria-hidden>&rarr;</span>
                    <span>{t.stateTo}</span>
                  </div>
                  <div className="font-mono text-[0.7rem] text-apg-ash">
                    {t.reason ?? '—'}
                  </div>
                </li>
              ))}
            </ol>
          )}
        </CollapsibleContent>
      </Collapsible>
    </div>
  )
}

function secondsSince(startMs: number): number {
  return (Date.now() - startMs) / 1000
}

function secondsBetweenTransitions(transitions: Transition[]): number | null {
  const start = Date.parse(transitions[0].createdAt)
  const end = Date.parse(transitions[transitions.length - 1].createdAt)
  if (Number.isNaN(start) || Number.isNaN(end)) return null
  return Math.max(0, (end - start) / 1000)
}
