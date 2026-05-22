import { useRef } from 'react'
import { SCENARIOS, type Scenario, type ScenarioFlag } from '@/lib/scenarios'
import { cn } from '@/lib/utils'

/**
 * Right-hand column of three Roman-numeralled scenario chips. Behaves as a
 * radio group: ArrowDown/ArrowUp navigate, Enter/Space (re)select, Tab moves
 * out of the group.
 */
type Props = {
  selected: ScenarioFlag
  onSelect: (flag: ScenarioFlag) => void
  disabled?: boolean
}

export function ScenarioChips({ selected, onSelect, disabled }: Props) {
  const chipRefs = useRef<Array<HTMLButtonElement | null>>([])

  const selectedIndex = SCENARIOS.findIndex((s) => s.flag === selected)

  const onKeyDown = (
    event: React.KeyboardEvent<HTMLButtonElement>,
    index: number,
  ) => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowRight') {
      event.preventDefault()
      const next = (index + 1) % SCENARIOS.length
      onSelect(SCENARIOS[next].flag)
      chipRefs.current[next]?.focus()
    } else if (event.key === 'ArrowUp' || event.key === 'ArrowLeft') {
      event.preventDefault()
      const prev = (index - 1 + SCENARIOS.length) % SCENARIOS.length
      onSelect(SCENARIOS[prev].flag)
      chipRefs.current[prev]?.focus()
    }
  }

  return (
    <div
      role="radiogroup"
      aria-label="scenario"
      className="flex flex-col gap-apg-4"
    >
      {SCENARIOS.map((scenario, index) => (
        <ScenarioChip
          key={scenario.flag}
          scenario={scenario}
          selected={scenario.flag === selected}
          // Only the active chip is tab-stoppable so Tab exits the group cleanly.
          tabIndex={
            scenario.flag === selected || (selectedIndex === -1 && index === 0)
              ? 0
              : -1
          }
          disabled={disabled}
          onSelect={() => onSelect(scenario.flag)}
          onKeyDown={(e) => onKeyDown(e, index)}
          ref={(el) => {
            chipRefs.current[index] = el
          }}
        />
      ))}
    </div>
  )
}

type ChipProps = {
  scenario: Scenario
  selected: boolean
  tabIndex: number
  disabled?: boolean
  onSelect: () => void
  onKeyDown: (e: React.KeyboardEvent<HTMLButtonElement>) => void
  ref: (el: HTMLButtonElement | null) => void
}

function ScenarioChip({
  scenario,
  selected,
  tabIndex,
  disabled,
  onSelect,
  onKeyDown,
  ref,
}: ChipProps) {
  return (
    <button
      ref={ref}
      type="button"
      role="radio"
      aria-checked={selected}
      tabIndex={tabIndex}
      disabled={disabled}
      onClick={onSelect}
      onKeyDown={onKeyDown}
      className={cn(
        'group relative flex flex-col gap-apg-1 py-apg-2 pl-apg-4 pr-apg-3 text-left',
        'transition-colors focus-visible:outline-none focus-visible:bg-apg-surface-2',
        // 3px brass left-rule when selected — replaces the chip "pill" entirely.
        selected
          ? 'border-l-[3px] border-apg-brass bg-apg-surface-2'
          : 'border-l-[3px] border-transparent hover:bg-apg-surface',
        disabled && 'cursor-not-allowed opacity-60',
      )}
    >
      <div className="flex items-baseline gap-apg-2">
        <span className="font-serif italic text-apg-ash text-base leading-none">
          {scenario.numeral}.
        </span>
        <span className="font-sans text-sm text-apg-bone">
          {scenario.title}
        </span>
      </div>
      <span className="font-mono text-[0.7rem] uppercase tracking-wider text-apg-ash">
        expected: {scenario.expected}
      </span>
    </button>
  )
}
