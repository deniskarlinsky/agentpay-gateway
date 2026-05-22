import { env } from './env'

/**
 * Placeholder shell for the buyer simulator UI.
 *
 * This is a scaffold — Task 3 replaces the contents with the real intent
 * ticket, scenario chips, saga state machine, decision plane, and receipt
 * described in `docs/buyer-simulator-ux.md`.
 *
 * The body intentionally renders raw type against the theme background so a
 * reviewer can eyeball the palette (warm-black ink, bone text), the font
 * stack (Geist body, Geist Mono fixed-width, Instrument Serif display), and
 * the noise overlay before any real components are wired up.
 */
function App() {
  return (
    <main className="relative z-10 mx-auto flex min-h-screen max-w-3xl flex-col gap-apg-5 px-apg-5 py-apg-7 text-apg-bone">
      <header className="border-b border-apg-rule pb-apg-4">
        <h1 className="font-serif text-4xl leading-none tracking-tight">
          AgentPay
        </h1>
        <p className="mt-apg-2 font-mono text-xs uppercase tracking-[0.18em] text-apg-ash">
          buyer simulator · scaffold
        </p>
      </header>
      <section className="space-y-apg-3 font-sans text-sm text-apg-ash">
        <p>
          This module is a scaffold. The real UI is implemented in a follow-up
          task per the UX spec at{' '}
          <code className="font-mono text-apg-bone">
            docs/buyer-simulator-ux.md
          </code>
          .
        </p>
        <p>
          Gateway base URL:{' '}
          <code className="font-mono text-apg-bone">{env.gatewayBaseUrl}</code>
        </p>
      </section>
    </main>
  )
}

export default App
