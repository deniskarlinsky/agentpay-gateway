import { Button } from '@/components/ui/button'
import {
  DEFAULT_AMOUNT_CAP_PLAIN,
  DEFAULT_AMOUNT_PLAIN,
  DEFAULT_DESCRIPTION,
  DEFAULT_MERCHANT_ID,
  DEFAULT_MERCHANT_NAME,
  type Scenario,
} from '@/lib/scenarios'
import { HonestyNote } from './HonestyNote'

/**
 * The intent ticket — hairline-ruled stub on the left of the page that shows
 * the values about to be signed and submitted. Labels in Geist Mono small-caps,
 * values in Geist sans (inverted-weight pattern from UX doc §2).
 *
 * The form is "displayed" rather than "editable" for the demo — amount and
 * description are read-only because the scenario fixtures drive the prompts.
 * Future enhancement (UX doc §7): a fourth Custom chip with free-form input.
 */
type Props = {
  scenario: Scenario
  onSubmit: () => void
  submitting: boolean
  submitDisabled: boolean
  keyReady: boolean
}

export function IntentTicket({
  scenario,
  onSubmit,
  submitting,
  submitDisabled,
  keyReady,
}: Props) {
  return (
    <div>
      <div className="border border-apg-rule bg-apg-surface px-apg-5 py-apg-5">
        <header className="mb-apg-4 flex items-center justify-between border-b border-apg-rule pb-apg-2">
          <h2 className="font-mono text-[0.7rem] uppercase tracking-[0.18em] text-apg-ash">
            Intent Ticket
          </h2>
        </header>

        <dl className="grid grid-cols-1 gap-apg-4">
          <Field label="buyer agent">
            <div className="font-sans text-base text-apg-bone">shop-bot</div>
            <div className="font-mono text-[0.7rem] text-apg-ash">
              on behalf of {scenario.buyerName}
            </div>
          </Field>

          <Field label="merchant">
            <div className="font-sans text-base text-apg-bone">
              {DEFAULT_MERCHANT_ID}
            </div>
            <div className="font-mono text-[0.7rem] text-apg-ash">
              &ldquo;{DEFAULT_MERCHANT_NAME}&rdquo;
            </div>
          </Field>

          <Field label="amount">
            <div className="flex items-baseline gap-apg-3">
              <span className="font-sans text-base text-apg-bone">
                <span className="apg-loadbearing">${DEFAULT_AMOUNT_PLAIN}</span>
              </span>
              <span className="font-mono text-[0.7rem] uppercase tracking-wider text-apg-ash">
                cap ${DEFAULT_AMOUNT_CAP_PLAIN}
              </span>
            </div>
          </Field>

          <Field label="description">
            <div className="font-sans text-base text-apg-bone">
              {DEFAULT_DESCRIPTION}
            </div>
          </Field>
        </dl>

        <div className="mt-apg-5 flex justify-end">
          <Button
            type="button"
            onClick={onSubmit}
            disabled={submitDisabled}
            aria-busy={submitting}
            className="font-mono text-xs uppercase tracking-[0.18em]"
          >
            {submitting ? 'submitting…' : 'Run AgentPay simulation'}
          </Button>
        </div>
      </div>

      <HonestyNote keyReady={keyReady} />
    </div>
  )
}

function Field({
  label,
  children,
}: {
  label: string
  children: React.ReactNode
}) {
  return (
    <div className="grid grid-cols-1 gap-apg-1">
      <dt className="font-mono text-[0.65rem] uppercase tracking-[0.18em] text-apg-ash">
        {label}
      </dt>
      <dd className="m-0">{children}</dd>
    </div>
  )
}
