/**
 * TypeScript mirror of {@code com.agentpay.buyer.scenarios.Scenario}. The
 * orchestrator's decision-plane prompts key off the exact agent_metadata
 * values; this constant MUST stay byte-equivalent to the Java enum. If the
 * enum changes, change this and the UX doc §3 table together.
 *
 * (Future enhancement noted in docs/buyer-simulator-ux.md §7: expose the
 * fixtures as a gateway endpoint so this duplication goes away.)
 */
export type ScenarioFlag = 'happy' | 'compliance-fail' | 'review'

export type ExpectedTerminal =
  | 'COMMITTED'
  | 'DECLINED'
  | 'SUSPENDED_FOR_REVIEW'

export type Scenario = Readonly<{
  flag: ScenarioFlag
  /** Roman numeral as it appears on the chip, in editorial italic. */
  numeral: 'I' | 'II' | 'III'
  /** Display name on the chip. */
  title: string
  buyerName: string
  expected: ExpectedTerminal
  /**
   * Identical to {@code Scenario.agentMetadata()} on the Java side, used in
   * the {@code agent_metadata} field of POST /payments. {@link Readonly} on
   * the outer shape; entries themselves are intentionally string-to-string
   * to match the Java {@code Map<String,String>} contract.
   */
  agentMetadata: Readonly<Record<string, string>>
}>

export const SCENARIOS: readonly Scenario[] = [
  {
    flag: 'happy',
    numeral: 'I',
    title: 'Happy path',
    buyerName: 'Alice Buyer',
    expected: 'COMMITTED',
    agentMetadata: {
      'buyer.name': 'Alice Buyer',
      'buyer.country': 'US',
      'merchant.country': 'US',
      crossBorder: 'false',
      amountBand: 'small',
    },
  },
  {
    flag: 'compliance-fail',
    numeral: 'II',
    title: 'Compliance fail',
    buyerName: 'Fictitious Bad Actor',
    expected: 'DECLINED',
    agentMetadata: {
      'buyer.name': 'Fictitious Bad Actor',
      'buyer.country': 'XX',
      'merchant.name': 'Acme Widgets Ltd',
      'merchant.country': 'US',
      crossBorder: 'true',
      amountBand: 'small',
    },
  },
  {
    flag: 'review',
    numeral: 'III',
    title: 'High-risk review',
    buyerName: 'Risky Reviewer',
    expected: 'SUSPENDED_FOR_REVIEW',
    agentMetadata: {
      'buyer.name': 'Risky Reviewer',
      'buyer.country': 'GB',
      'merchant.name': 'Acme Widgets Ltd',
      'merchant.country': 'US',
      crossBorder: 'true',
      amountBand: 'large',
      'buyer.firstSeenAt': '2026-05-18T09:00:00Z',
    },
  },
] as const

export const DEFAULT_MERCHANT_ID = 'merchant-acme'
export const DEFAULT_MERCHANT_NAME = 'Acme Widgets Ltd'
export const DEFAULT_AMOUNT_PLAIN = '42.50'
export const DEFAULT_AMOUNT_CAP_PLAIN = '50.00'
export const DEFAULT_CURRENCY = 'USD'
export const DEFAULT_DESCRIPTION = 'SKU-42 widget'
export const DEFAULT_AGENT_ID = 'shop-bot'
export const DEFAULT_INTENT_TTL_SECONDS = 300
