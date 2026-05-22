/**
 * The "this is a simulator" disclosure that sits under the intent ticket.
 * Wording fixed in UX doc §5 — copy MUST match so future code reviews can
 * grep for it.
 */
type Props = { keyReady: boolean }

export function HonestyNote({ keyReady }: Props) {
  return (
    <div className="mt-apg-3 max-w-[28rem]">
      <p className="font-mono text-[0.7rem] italic leading-relaxed text-apg-ash">
        simulating what a real AI agent would do — this demo mints a session
        keypair in your browser; a production agent ships with its own signing
        key
      </p>
      {!keyReady && (
        <p className="mt-apg-2 font-mono text-[0.7rem] italic text-apg-ash">
          minting agent key&hellip;
        </p>
      )}
    </div>
  )
}
