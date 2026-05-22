import { useEffect, useRef, useState } from 'react'
import {
  generateAgentKey,
  pemFromSpki,
  signCanonical,
  type AgentKeyPair,
} from './agentKey'

/**
 * Generates an RSA-2048 keypair on mount and exposes it through stable
 * helpers. The pair lives in a {@link useRef} — never in render state — so
 * re-renders never trigger regeneration. The {@code ready} flag triggers
 * re-renders so consumers can enable the submit button.
 *
 * Lifecycle (per UX doc §5): the private key is non-extractable; the pair is
 * never written to any persistence surface. Reloading mints a new key.
 */
export type UseAgentKey = {
  ready: boolean
  /** PEM-encoded SPKI public key, ready for POST /intent-tokens. */
  publicKeyPem: string | null
  /** Sign canonical bytes; returns base64 signature. Throws if not ready. */
  sign: (bytes: Uint8Array) => Promise<string>
}

export function useAgentKey(): UseAgentKey {
  const keyRef = useRef<AgentKeyPair | null>(null)
  const [publicKeyPem, setPublicKeyPem] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    void (async () => {
      const pair = await generateAgentKey()
      if (cancelled) return
      keyRef.current = pair
      const pem = await pemFromSpki(pair.publicKey)
      if (cancelled) return
      setPublicKeyPem(pem)
    })()
    return () => {
      cancelled = true
    }
  }, [])

  const sign = async (bytes: Uint8Array): Promise<string> => {
    const pair = keyRef.current
    if (!pair) {
      throw new Error('agent key not ready')
    }
    return signCanonical(pair.privateKey, bytes)
  }

  return { ready: publicKeyPem !== null, publicKeyPem, sign }
}
