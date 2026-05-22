/**
 * Web Crypto helpers for the in-browser buyer agent keypair (RSA-2048,
 * RSASSA-PKCS1-v1_5 / SHA-256). Matches the existing Java buyer-client's
 * signature scheme so the gateway's TODO signature verification can wire up
 * without UI changes.
 *
 * Lifecycle (per the UX doc §5 "Signing"): the keypair lives ONLY in the
 * JavaScript heap. It MUST NOT touch localStorage, sessionStorage, IndexedDB,
 * or cookies. The private key is generated with {@code extractable: false}
 * so even an accidental export attempt throws.
 */

export type AgentKeyPair = {
  publicKey: CryptoKey
  privateKey: CryptoKey
}

/**
 * Generate a fresh RSA-2048 keypair. 50–300ms on a desktop browser — callers
 * should disable the submit button until the promise settles.
 */
export async function generateAgentKey(): Promise<AgentKeyPair> {
  const pair = await crypto.subtle.generateKey(
    {
      name: 'RSASSA-PKCS1-v1_5',
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: 'SHA-256',
    },
    // extractable=false applies to BOTH halves of the pair when generateKey
    // returns CryptoKeyPair. The Web Crypto spec then permits exportKey on
    // the *public* key regardless (public material is not sensitive).
    false,
    ['sign', 'verify'],
  )
  return { publicKey: pair.publicKey, privateKey: pair.privateKey }
}

/** Sign canonical bytes; returns base64-encoded signature (standard alphabet). */
export async function signCanonical(
  privateKey: CryptoKey,
  bytes: Uint8Array,
): Promise<string> {
  // Web Crypto's TS types want an ArrayBuffer-backed BufferSource. The
  // TextEncoder we used to produce these bytes already yields one, but we
  // re-wrap to satisfy the stricter `ArrayBufferView<ArrayBuffer>` constraint
  // introduced in recent @types/dom updates without surprising the caller.
  const buf = bytes.buffer.slice(
    bytes.byteOffset,
    bytes.byteOffset + bytes.byteLength,
  ) as ArrayBuffer
  const sig = await crypto.subtle.sign(
    { name: 'RSASSA-PKCS1-v1_5' },
    privateKey,
    buf,
  )
  return bytesToBase64(new Uint8Array(sig))
}

/**
 * Export a public key as a PEM-encoded SPKI block matching what the gateway's
 * {@code BuyerKeyStore.publicKeyPem(...)} emits — 64-char lines between
 * BEGIN/END PUBLIC KEY markers.
 */
export async function pemFromSpki(publicKey: CryptoKey): Promise<string> {
  const spki = await crypto.subtle.exportKey('spki', publicKey)
  const b64 = bytesToBase64(new Uint8Array(spki))
  const wrapped = b64.match(/.{1,64}/g)?.join('\n') ?? b64
  return `-----BEGIN PUBLIC KEY-----\n${wrapped}\n-----END PUBLIC KEY-----`
}

function bytesToBase64(bytes: Uint8Array): string {
  // String.fromCharCode + btoa is the most compact pure-browser route and
  // avoids pulling in a buffer polyfill. Chunked to stay well under the
  // 65,536 argument limit some engines impose on fromCharCode.
  let binary = ''
  const chunk = 0x8000
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk))
  }
  return btoa(binary)
}
