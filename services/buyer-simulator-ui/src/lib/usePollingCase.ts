import { useEffect, useRef } from 'react'
import { useQuery, type UseQueryResult } from '@tanstack/react-query'
import {
  ApiError,
  getCase,
  getCaseTransitions,
  type CaseStatus,
  type CaseTransitions,
} from '@/api/agentpay'
import { isTerminal } from './sagaStates'

/**
 * Polls both {@code /cases/{id}} and {@code /cases/{id}/transitions} at 1Hz
 * while the simulator is in {@code polling} mode. Stops polling either
 * endpoint once the case is terminal. The transitions endpoint 404s pre-Task-4;
 * we surface that as a soft-degrade (no error mode, no toast) so the rest of
 * the UI keeps working.
 *
 * The 60s deadline lives here too: a single {@link setTimeout} fires
 * {@code onTimeout} if the case never finalises in time. The timer resets
 * whenever {@code caseId} or {@code startedAt} changes.
 */
export type PollingResult = {
  caseQuery: UseQueryResult<CaseStatus, ApiError>
  transitionsQuery: UseQueryResult<CaseTransitions, ApiError>
}

export type UsePollingCaseArgs = {
  caseId: string | null
  /** {@code performance.now()}-style marker so the deadline ticks per attempt. */
  startedAt: number | null
  enabled: boolean
  onTimeout: (lastSeenState: string | undefined) => void
}

export function usePollingCase({
  caseId,
  startedAt,
  enabled,
  onTimeout,
}: UsePollingCaseArgs): PollingResult {
  const caseQuery = useQuery<CaseStatus, ApiError>({
    queryKey: ['case', caseId],
    queryFn: () => getCase(caseId as string),
    enabled: enabled && !!caseId,
    refetchInterval: (q) => (isTerminal(q.state.data?.state) ? false : 1000),
    refetchOnWindowFocus: false,
    retry: false,
  })

  const transitionsQuery = useQuery<CaseTransitions, ApiError>({
    queryKey: ['case-transitions', caseId],
    queryFn: () => getCaseTransitions(caseId as string),
    enabled: enabled && !!caseId,
    refetchInterval: (q) =>
      isTerminal(q.state.data?.currentState) ? false : 1000,
    refetchOnWindowFocus: false,
    // 404 pre-Task-4 is expected; don't hammer with retries.
    retry: false,
  })

  // 60s deadline. The timer is bound to caseId+startedAt so a re-run starts
  // a fresh deadline. We deliberately read the latest case state from a ref
  // inside the timer callback to avoid resetting the timeout on every poll.
  const lastSeenRef = useRef<string | undefined>(undefined)
  lastSeenRef.current = caseQuery.data?.state

  useEffect(() => {
    if (!enabled || !caseId || startedAt === null) return
    const handle = window.setTimeout(() => {
      if (!isTerminal(lastSeenRef.current)) {
        onTimeout(lastSeenRef.current)
      }
    }, 60_000)
    return () => window.clearTimeout(handle)
    // onTimeout is provided by the parent as a stable callback (via App's
    // setMode setter); we intentionally exclude it from deps to avoid
    // resetting the deadline when React re-renders the parent.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [caseId, startedAt, enabled])

  return { caseQuery, transitionsQuery }
}
