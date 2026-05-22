import { useEffect, useRef, useState } from 'react'
import {
  BRANCH_STATES,
  FORWARD_STATES,
  forwardIndex,
  type SagaState,
} from '@/lib/sagaStates'
import { cn } from '@/lib/utils'

/**
 * Saga state machine (hero). Renders the six forward states INITIATED → HELD →
 * REVIEWING → APPROVED → ROUTED → COMMITTED as monospace-labelled HTML boxes
 * connected by SVG hairline strokes. Three branch labels (DECLINED,
 * SUSPENDED_FOR_REVIEW, COMPENSATED) float below the main chain, dim by
 * default and brightening when the case enters them.
 *
 * Stroke-trace animation per UX doc §4 "Motion": every time the lit forward
 * index advances, the connector to the new bubble animates an 80ms
 * stroke-dashoffset trace. Stroke colour brightens from rule-grey to brass.
 */
type Props = {
  /** Current saga state from {@code /cases/{id}.state}. Drives lit-bubble. */
  currentState: string | null
  elapsedSeconds: number | null
}

export function SagaStateMachine({ currentState, elapsedSeconds }: Props) {
  // Track the previous forward index so we can fire stroke-trace animations.
  const [prevForward, setPrevForward] = useState<number>(-1)
  const currentForward = forwardIndex(currentState)
  const branch = (
    BRANCH_STATES as readonly string[]
  ).includes(currentState ?? '')
    ? (currentState as (typeof BRANCH_STATES)[number])
    : null

  useEffect(() => {
    if (currentForward > prevForward) {
      setPrevForward(currentForward)
    } else if (currentForward === -1 && prevForward !== -1 && !branch) {
      // Reset on a new run (back to idle).
      setPrevForward(-1)
    }
  }, [currentForward, prevForward, branch])

  return (
    <section className="flex flex-col gap-apg-4">
      <header className="flex items-baseline justify-between">
        <h2 className="font-mono text-[0.7rem] uppercase tracking-[0.18em] text-apg-ash">
          Saga
        </h2>
        {elapsedSeconds !== null && (
          <span className="font-mono text-[0.7rem] text-apg-ash">
            {elapsedSeconds.toFixed(2)}s elapsed
          </span>
        )}
      </header>

      <ForwardChain
        currentForward={currentForward}
        prevForward={prevForward}
        branch={branch}
      />

      <BranchLabels currentState={currentState as SagaState | null} />
    </section>
  )
}

type ForwardChainProps = {
  currentForward: number
  prevForward: number
  branch: (typeof BRANCH_STATES)[number] | null
}

function ForwardChain({
  currentForward,
  prevForward,
  branch,
}: ForwardChainProps) {
  // The brightest "lit" index is whichever forward index is currently shown,
  // OR — when on a branch — the index of the branching predecessor (e.g.
  // DECLINED implies HELD/REVIEWING already lit). For our purposes we just
  // keep the highest forward index reached as the high-water mark.
  const litIndex = Math.max(currentForward, prevForward)

  return (
    <>
      {/* Desktop: horizontal */}
      <div className="hidden md:block">
        <HorizontalChain
          litIndex={litIndex}
          currentForward={currentForward}
          branch={branch}
        />
      </div>
      {/* Mobile: vertical */}
      <div className="block md:hidden">
        <VerticalChain
          litIndex={litIndex}
          currentForward={currentForward}
          branch={branch}
        />
      </div>
    </>
  )
}

type ChainProps = {
  litIndex: number
  currentForward: number
  branch: (typeof BRANCH_STATES)[number] | null
}

function HorizontalChain({ litIndex, currentForward, branch }: ChainProps) {
  return (
    <ol className="grid grid-cols-6 items-center gap-apg-1">
      {FORWARD_STATES.map((state, i) => (
        <li key={state} className="flex items-center">
          <Bubble
            label={state}
            traversed={i < litIndex || (i === litIndex && branch !== null)}
            current={i === currentForward && currentForward !== -1}
            future={i > litIndex}
            horizontal
          />
          {i < FORWARD_STATES.length - 1 && (
            <Connector
              traversed={i < litIndex}
              animateOnArrival={i === litIndex - 1 && litIndex > 0}
              orientation="horizontal"
            />
          )}
        </li>
      ))}
    </ol>
  )
}

function VerticalChain({ litIndex, currentForward, branch }: ChainProps) {
  return (
    <ol className="flex flex-col">
      {FORWARD_STATES.map((state, i) => (
        <li
          key={state}
          className="flex flex-col items-center"
        >
          <Bubble
            label={state}
            traversed={i < litIndex || (i === litIndex && branch !== null)}
            current={i === currentForward && currentForward !== -1}
            future={i > litIndex}
            horizontal={false}
          />
          {i < FORWARD_STATES.length - 1 && (
            <Connector
              traversed={i < litIndex}
              animateOnArrival={i === litIndex - 1 && litIndex > 0}
              orientation="vertical"
            />
          )}
        </li>
      ))}
    </ol>
  )
}

type BubbleProps = {
  label: string
  traversed: boolean
  current: boolean
  future: boolean
  horizontal: boolean
}

function Bubble({
  label,
  traversed,
  current,
  future,
  horizontal,
}: BubbleProps) {
  return (
    <div
      className={cn(
        'flex flex-col items-center',
        horizontal ? 'min-w-0 grow' : 'w-full',
      )}
    >
      <div
        aria-current={current ? 'step' : undefined}
        className={cn(
          'flex items-center justify-center border px-apg-2 py-apg-2 text-center',
          // Sizing: tight, monospace labels need ~9.5ch wide.
          'min-h-[2.5rem] w-full font-mono text-[0.7rem] uppercase leading-tight tracking-tight',
          future
            ? 'border-apg-rule text-apg-ash opacity-35'
            : current
              ? 'border-apg-brass bg-apg-surface text-apg-bone apg-halo'
              : traversed
                ? 'border-apg-brass/70 text-apg-bone'
                : 'border-apg-rule text-apg-bone',
        )}
      >
        {label}
      </div>
      <div
        className={cn(
          'mt-apg-1 font-mono text-[0.7rem] leading-none',
          traversed && !current ? 'text-apg-brass' : 'text-transparent',
        )}
        aria-hidden={!traversed || current}
      >
        ✓
      </div>
    </div>
  )
}

type ConnectorProps = {
  traversed: boolean
  animateOnArrival: boolean
  orientation: 'horizontal' | 'vertical'
}

function Connector({
  traversed,
  animateOnArrival,
  orientation,
}: ConnectorProps) {
  // We render each connector as a tiny inline SVG so we can animate
  // stroke-dashoffset cleanly. 80ms ease-out per UX doc §4.
  const pathRef = useRef<SVGPathElement | null>(null)
  const [length, setLength] = useState<number | null>(null)

  useEffect(() => {
    const path = pathRef.current
    if (path) {
      setLength(path.getTotalLength())
    }
  }, [])

  const stroke = traversed ? '#E8C547' : '#26262B'

  if (orientation === 'horizontal') {
    return (
      <svg
        className="mx-apg-1 h-[2px] w-apg-4 shrink-0"
        viewBox="0 0 16 2"
        aria-hidden
      >
        <path
          ref={pathRef}
          d="M0 1 L16 1"
          stroke={stroke}
          strokeWidth={1}
          strokeOpacity={traversed ? 1 : 0.35}
          strokeDasharray={length ?? undefined}
          strokeDashoffset={
            traversed ? 0 : length !== null && animateOnArrival ? length : 0
          }
          style={{
            transition: animateOnArrival
              ? 'stroke-dashoffset 80ms cubic-bezier(0.16, 1, 0.3, 1)'
              : undefined,
          }}
        />
      </svg>
    )
  }
  return (
    <svg
      className="my-apg-1 h-apg-4 w-[2px] shrink-0"
      viewBox="0 0 2 16"
      aria-hidden
    >
      <path
        ref={pathRef}
        d="M1 0 L1 16"
        stroke={stroke}
        strokeWidth={1}
        strokeOpacity={traversed ? 1 : 0.35}
        strokeDasharray={length ?? undefined}
        strokeDashoffset={
          traversed ? 0 : length !== null && animateOnArrival ? length : 0
        }
        style={{
          transition: animateOnArrival
            ? 'stroke-dashoffset 80ms cubic-bezier(0.16, 1, 0.3, 1)'
            : undefined,
        }}
      />
    </svg>
  )
}

function BranchLabels({ currentState }: { currentState: SagaState | null }) {
  return (
    <div className="flex flex-col gap-apg-1 pl-apg-5 font-mono text-[0.7rem]">
      {BRANCH_STATES.map((branch) => {
        const active = currentState === branch
        return (
          <div
            key={branch}
            className={cn(
              'flex items-center gap-apg-2',
              active
                ? branch === 'DECLINED' || branch === 'COMPENSATED'
                  ? 'text-apg-rose'
                  : 'text-apg-amber'
                : 'text-apg-ash opacity-35',
            )}
          >
            <span aria-hidden>&#9492;&#9472;&#9472;&#9472;&#9472;&gt;</span>
            <span>{branch}</span>
          </div>
        )
      })}
    </div>
  )
}
