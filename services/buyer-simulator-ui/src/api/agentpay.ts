/**
 * Typed fetch client for the four gateway endpoints the simulator drives.
 * Wire shapes match the Java records under
 * {@code services/gateway/src/main/java/com/agentpay/gateway/api/*} and the
 * {@code /cases/{id}/transitions} shape pinned in
 * {@code docs/buyer-simulator-ux.md §5}.
 *
 * All field names are snake_case on the wire; this module maps them to
 * camelCase explicitly via field-by-field destructuring (no {@code any}
 * casts). The {@code /cases/{id}/transitions} endpoint 404s pre-Task-4 —
 * callers handle that as a soft-degrade (specialist cards stay skeleton).
 */
import { env } from '@/env'

export type IntentTokenRequest = {
  agentId: string
  agentPubkey: string
  merchantId: string
  amountCap: string
  currency: string
  scope: string
  ttlSeconds: number
}

export type IntentTokenResponse = {
  intentToken: string
  expiresAt: string
}

export type PaymentRequest = {
  caseId: string
  merchantId: string
  /** Plain decimal string ({@code BigDecimal.toPlainString()} equivalent). */
  amount: string
  currency: string
  description: string
  buyerSignature: string
  agentMetadata: Record<string, string>
}

export type PaymentResponse = {
  caseId: string
  status: string
  traceUrl: string
}

export type PspOutcome = {
  status: string
  reasonCode: string | null
}

export type CaseStatus = {
  caseId: string
  state: string
  /** Persisted decision JSONB — opaque object/array, may be null pre-terminal. */
  decision: Record<string, unknown> | null
  pspOutcome: PspOutcome | null
  traceUrl: string
}

export type Transition = {
  stateFrom: string | null
  stateTo: string
  reason: string | null
  createdAt: string
}

export type Verdict = {
  agentName: string
  model: string
  verdictJson: Record<string, unknown>
  latencyMs: number
  costUsd: number
  createdAt: string
}

export type CaseTransitions = {
  caseId: string
  currentState: string
  transitions: Transition[]
  verdicts: Verdict[]
}

export class ApiError extends Error {
  readonly status: number
  readonly body: unknown
  constructor(status: number, body: unknown, message: string) {
    super(message)
    this.status = status
    this.body = body
    this.name = 'ApiError'
  }
}

async function request<T>(
  path: string,
  init?: RequestInit & { body?: BodyInit | null },
): Promise<T> {
  const res = await fetch(`${env.gatewayBaseUrl}${path}`, {
    ...init,
    headers: {
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      Accept: 'application/json',
      ...(init?.headers ?? {}),
    },
  })
  if (!res.ok) {
    let body: unknown = null
    try {
      body = await res.json()
    } catch {
      try {
        body = await res.text()
      } catch {
        body = null
      }
    }
    throw new ApiError(
      res.status,
      body,
      `gateway ${init?.method ?? 'GET'} ${path} returned ${res.status}`,
    )
  }
  return (await res.json()) as T
}

export async function postIntentToken(
  req: IntentTokenRequest,
): Promise<IntentTokenResponse> {
  const raw = await request<{ intent_token: string; expires_at: string }>(
    '/intent-tokens',
    {
      method: 'POST',
      body: JSON.stringify({
        agent_id: req.agentId,
        agent_pubkey: req.agentPubkey,
        merchant_id: req.merchantId,
        amount_cap: req.amountCap,
        currency: req.currency,
        scope: req.scope,
        ttl_seconds: req.ttlSeconds,
      }),
    },
  )
  return { intentToken: raw.intent_token, expiresAt: raw.expires_at }
}

export async function postPayment(
  bearer: string,
  req: PaymentRequest,
): Promise<PaymentResponse> {
  const raw = await request<{
    case_id: string
    status: string
    trace_url: string
  }>('/payments', {
    method: 'POST',
    headers: { Authorization: `Bearer ${bearer}` },
    body: JSON.stringify({
      case_id: req.caseId,
      merchant_id: req.merchantId,
      amount: req.amount,
      currency: req.currency,
      description: req.description,
      buyer_signature: req.buyerSignature,
      agent_metadata: req.agentMetadata,
    }),
  })
  return {
    caseId: raw.case_id,
    status: raw.status,
    traceUrl: raw.trace_url,
  }
}

type RawCaseStatus = {
  case_id: string
  state: string
  decision: Record<string, unknown> | null
  psp_outcome: { status: string; reason_code: string | null } | null
  trace_url: string
}

export async function getCase(caseId: string): Promise<CaseStatus> {
  const raw = await request<RawCaseStatus>(
    `/cases/${encodeURIComponent(caseId)}`,
  )
  return {
    caseId: raw.case_id,
    state: raw.state,
    decision: raw.decision,
    pspOutcome: raw.psp_outcome
      ? {
          status: raw.psp_outcome.status,
          reasonCode: raw.psp_outcome.reason_code,
        }
      : null,
    traceUrl: raw.trace_url,
  }
}

type RawTransition = {
  state_from: string | null
  state_to: string
  reason: string | null
  created_at: string
}

type RawVerdict = {
  agent_name: string
  model: string
  verdict_json: Record<string, unknown>
  latency_ms: number
  cost_usd: number
  created_at: string
}

type RawCaseTransitions = {
  case_id: string
  current_state: string
  transitions: RawTransition[]
  verdicts: RawVerdict[]
}

export async function getCaseTransitions(
  caseId: string,
): Promise<CaseTransitions> {
  const raw = await request<RawCaseTransitions>(
    `/cases/${encodeURIComponent(caseId)}/transitions`,
  )
  return {
    caseId: raw.case_id,
    currentState: raw.current_state,
    transitions: raw.transitions.map((t) => ({
      stateFrom: t.state_from,
      stateTo: t.state_to,
      reason: t.reason,
      createdAt: t.created_at,
    })),
    verdicts: raw.verdicts.map((v) => ({
      agentName: v.agent_name,
      model: v.model,
      verdictJson: v.verdict_json,
      latencyMs: v.latency_ms,
      costUsd: v.cost_usd,
      createdAt: v.created_at,
    })),
  }
}
