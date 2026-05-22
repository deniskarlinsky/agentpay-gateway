import { useState } from 'react'
import { ChevronRight } from 'lucide-react'
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from '@/components/ui/collapsible'
import { Skeleton } from '@/components/ui/skeleton'
import type { Verdict } from '@/api/agentpay'
import { cn } from '@/lib/utils'

/**
 * One specialist verdict panel — Risk, Compliance, or Routing. Five content
 * rows per UX doc §2 "Decision plane":
 *   1. Title (sans) + model id (mono)
 *   2. Primary verdict (serif numeral or 4-letter caps)
 *   3. Sub-verdict (mono small)
 *   4. Latency · cost (mono)
 *   5. Expandable rationale on click
 *
 * Skeleton → content swap is in-place; no entrance animation.
 */

export type SpecialistKind = 'Risk' | 'Compliance' | 'Routing'

type Props = {
  kind: SpecialistKind
  /** {@code null} → skeleton state. */
  verdict: Verdict | null
  /** Soft-degrade caption when /transitions endpoint is unavailable. */
  degraded: boolean
}

export function SpecialistCard({ kind, verdict, degraded }: Props) {
  const [open, setOpen] = useState(false)

  return (
    <div className="flex w-full flex-col border border-apg-rule bg-apg-surface px-apg-5 py-apg-4">
      <header className="mb-apg-3">
        <h3 className="font-sans text-base leading-none text-apg-bone">
          {kind}
        </h3>
        <p className="mt-apg-1 font-mono text-[0.7rem] uppercase tracking-wider text-apg-ash">
          {verdict?.model ?? expectedModel(kind)}
        </p>
      </header>

      {verdict ? (
        <Body kind={kind} verdict={verdict} open={open} setOpen={setOpen} />
      ) : (
        <SkeletonBody degraded={degraded} />
      )}
    </div>
  )
}

function expectedModel(kind: SpecialistKind): string {
  // Matches the Anthropic model IDs in CLAUDE.md §8.
  switch (kind) {
    case 'Risk':
      return 'claude-sonnet-4-6'
    case 'Compliance':
      return 'claude-sonnet-4-6'
    case 'Routing':
      return 'claude-haiku-4-5'
  }
}

function SkeletonBody({ degraded }: { degraded: boolean }) {
  return (
    <div className="flex grow flex-col gap-apg-3">
      <Skeleton className="h-9 w-24" />
      <Skeleton className="h-3 w-32" />
      <Skeleton className="h-3 w-40" />
      {degraded && (
        <p className="mt-apg-2 font-mono text-[0.65rem] italic leading-relaxed text-apg-ash">
          transitions endpoint pending — wires up in Task 4
        </p>
      )}
    </div>
  )
}

function Body({
  kind,
  verdict,
  open,
  setOpen,
}: {
  kind: SpecialistKind
  verdict: Verdict
  open: boolean
  setOpen: (b: boolean) => void
}) {
  const primary = primaryVerdict(kind, verdict.verdictJson)
  const sub = subVerdict(kind, verdict.verdictJson)
  const rationale = stringField(verdict.verdictJson, 'rationale')

  return (
    <div className="flex grow flex-col gap-apg-3">
      <div
        className={cn(
          'font-serif text-3xl leading-none',
          primary.tone === 'success' && 'text-apg-emerald',
          primary.tone === 'review' && 'text-apg-amber',
          primary.tone === 'fail' && 'text-apg-rose',
          primary.tone === 'neutral' && 'text-apg-bone',
        )}
      >
        {primary.label}
      </div>

      {sub && (
        <div className="font-mono text-xs uppercase tracking-wider text-apg-ash">
          {sub}
        </div>
      )}

      <div className="font-mono text-[0.7rem] text-apg-ash">
        {formatSeconds(verdict.latencyMs)} ·{' '}
        {formatCost(verdict.costUsd)}
      </div>

      <Collapsible open={open} onOpenChange={setOpen} className="mt-apg-2">
        <CollapsibleTrigger asChild>
          <button
            type="button"
            className="apg-link inline-flex items-center gap-apg-1 font-mono text-[0.7rem] text-apg-ash hover:text-apg-bone focus-visible:outline-none focus-visible:text-apg-bone"
          >
            <ChevronRight
              className={cn(
                'h-3 w-3 transition-transform',
                open && 'rotate-90',
              )}
              aria-hidden
            />
            rationale
          </button>
        </CollapsibleTrigger>
        <CollapsibleContent className="mt-apg-2">
          <p className="font-mono text-[0.7rem] leading-relaxed text-apg-bone">
            {rationale ?? 'no rationale provided'}
          </p>
        </CollapsibleContent>
      </Collapsible>
    </div>
  )
}

type Primary = {
  label: string
  tone: 'success' | 'review' | 'fail' | 'neutral'
}

function primaryVerdict(
  kind: SpecialistKind,
  v: Record<string, unknown>,
): Primary {
  if (kind === 'Risk') {
    const score = numberField(v, 'score')
    if (score === null) return { label: '—', tone: 'neutral' }
    let tone: Primary['tone'] = 'success'
    if (score >= 80) tone = 'fail'
    else if (score >= 50) tone = 'review'
    return { label: `${score} / 100`, tone }
  }
  if (kind === 'Compliance') {
    const outcome = stringField(v, 'outcome')
    if (outcome === 'PASS') return { label: 'PASS', tone: 'success' }
    if (outcome === 'FAIL') return { label: 'FAIL', tone: 'fail' }
    return { label: outcome ?? '—', tone: 'neutral' }
  }
  // Routing
  const psp = stringField(v, 'psp_id')
  return { label: psp ?? '—', tone: 'neutral' }
}

function subVerdict(
  kind: SpecialistKind,
  v: Record<string, unknown>,
): string | null {
  if (kind === 'Risk') {
    const signals = v['signals']
    if (Array.isArray(signals) && signals.length === 0) return 'CLEAR'
    if (Array.isArray(signals)) return `${signals.length} signal(s)`
    return null
  }
  if (kind === 'Compliance') {
    const citations = v['citations']
    if (Array.isArray(citations) && citations.length === 0)
      return 'no SDN match'
    if (Array.isArray(citations))
      return `${citations.length} citation(s)`
    return null
  }
  // Routing
  const rate = numberField(v, 'expected_success_rate')
  const bps = numberField(v, 'expected_cost_bps')
  if (rate !== null && bps !== null) {
    return `${Math.round(rate * 100)}% · ${bps} bps`
  }
  return null
}

function stringField(
  v: Record<string, unknown>,
  key: string,
): string | null {
  const raw = v[key]
  return typeof raw === 'string' ? raw : null
}

function numberField(
  v: Record<string, unknown>,
  key: string,
): number | null {
  const raw = v[key]
  return typeof raw === 'number' ? raw : null
}

function formatSeconds(ms: number): string {
  return `${(ms / 1000).toFixed(2)}s`
}

function formatCost(usd: number): string {
  return `$${usd.toFixed(4)}`
}
