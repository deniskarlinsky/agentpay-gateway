import type { Verdict } from '@/api/agentpay'
import { SpecialistCard, type SpecialistKind } from './SpecialistCard'

/**
 * Decision-plane row — three specialist cards in fixed order Risk →
 * Compliance → Routing. Stacks vertically below 768px (UX doc §2 mobile).
 *
 * Verdicts are matched on agent_name. When /transitions is unavailable, all
 * three render skeletons with the soft-degrade caption.
 */
type Props = {
  verdicts: Verdict[]
  degraded: boolean
}

const ORDER: { kind: SpecialistKind; agentName: string }[] = [
  { kind: 'Risk', agentName: 'RiskAgent' },
  { kind: 'Compliance', agentName: 'ComplianceAgent' },
  { kind: 'Routing', agentName: 'RoutingAgent' },
]

export function DecisionPlane({ verdicts, degraded }: Props) {
  return (
    <section className="flex flex-col gap-apg-4">
      <header>
        <h2 className="font-mono text-[0.7rem] uppercase tracking-[0.18em] text-apg-ash">
          Decision plane
        </h2>
        <p className="mt-apg-1 font-mono text-[0.7rem] text-apg-ash">
          supervisor &rarr; fan-out &rarr; 3 specialists in parallel
        </p>
      </header>

      <div className="flex flex-col gap-apg-4 md:flex-row md:gap-apg-5">
        {ORDER.map(({ kind, agentName }) => {
          const verdict =
            verdicts.find((v) => v.agentName === agentName) ?? null
          return (
            <div key={kind} className="flex w-full md:basis-1/3">
              <SpecialistCard
                kind={kind}
                verdict={verdict}
                degraded={degraded && verdict === null}
              />
            </div>
          )
        })}
      </div>
    </section>
  )
}
