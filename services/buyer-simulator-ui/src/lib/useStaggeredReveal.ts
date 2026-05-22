import type { CSSProperties } from 'react'

/**
 * Page-load reveal cadence (UX doc §4 "Motion"): four ordered chunks fading
 * up with 60 / 140 / 220 / 320ms delays. We expose a tiny constant + helper
 * rather than a hook because the values are stateless — the animation is
 * pure CSS triggered on mount.
 */
export const REVEAL_DELAYS_MS = {
  header: 60,
  intent: 140,
  saga: 220,
  decisionPlane: 320,
} as const

export type RevealKey = keyof typeof REVEAL_DELAYS_MS

/**
 * Inline style for an element that should fade-up on mount. Pair with the
 * {@code apg-reveal} CSS class declared in {@code index.css}.
 */
export function revealStyle(key: RevealKey): CSSProperties {
  return { animationDelay: `${REVEAL_DELAYS_MS[key]}ms` }
}
