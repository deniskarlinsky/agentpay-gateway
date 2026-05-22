/**
 * Saga state ordering and branch metadata for the buyer simulator state
 * machine. Source-of-truth: `docs/saga-states.md` and FR-O-001 in
 * REQUIREMENTS.md (§5.2). Do NOT add states here without a corresponding
 * change to the orchestrator's {@code SagaState} enum.
 */

export const FORWARD_STATES = [
  'INITIATED',
  'HELD',
  'REVIEWING',
  'APPROVED',
  'ROUTED',
  'COMMITTED',
] as const

export type ForwardState = (typeof FORWARD_STATES)[number]

export const BRANCH_STATES = [
  'DECLINED',
  'SUSPENDED_FOR_REVIEW',
  'COMPENSATED',
] as const

export type BranchState = (typeof BRANCH_STATES)[number]

export type SagaState = ForwardState | BranchState

export const ALL_STATES: readonly SagaState[] = [
  ...FORWARD_STATES,
  ...BRANCH_STATES,
]

/**
 * Absorbing states — once the saga enters one, no more transitions are
 * possible and polling can stop.
 */
export const TERMINAL_STATES = new Set<SagaState>([
  'COMMITTED',
  'DECLINED',
  'COMPENSATED',
  'SUSPENDED_FOR_REVIEW',
])

export function isTerminal(state: string | undefined | null): boolean {
  return !!state && TERMINAL_STATES.has(state as SagaState)
}

/**
 * Index of a state on the forward chain. Returns -1 if the state is a branch
 * (DECLINED, SUSPENDED_FOR_REVIEW, COMPENSATED) — branches are rendered below
 * the main chain and do not advance the forward progression.
 */
export function forwardIndex(state: string | undefined | null): number {
  if (!state) return -1
  return FORWARD_STATES.indexOf(state as ForwardState)
}
