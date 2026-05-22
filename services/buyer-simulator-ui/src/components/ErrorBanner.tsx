import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { AlertTriangle } from 'lucide-react'

/**
 * Persistent hard-failure banner. Per UX doc §2 "Error": brass-on-rose with a
 * text-only "↻ retry" link that resets state. Transient polling errors get a
 * Sonner toast (handled in App).
 */
type Props = {
  title: string
  message: string
  onRetry?: () => void
}

export function ErrorBanner({ title, message, onRetry }: Props) {
  return (
    <Alert
      variant="destructive"
      className="border-apg-rose/40 bg-apg-rose/5"
      role="alert"
    >
      <AlertTriangle className="h-4 w-4" aria-hidden />
      <AlertTitle className="font-sans text-sm tracking-tight text-apg-rose">
        {title}
      </AlertTitle>
      <AlertDescription className="font-mono text-xs text-apg-ash">
        {message}
        {onRetry && (
          <>
            {' '}
            <button
              type="button"
              onClick={onRetry}
              className="apg-link inline font-mono text-xs text-apg-bone"
            >
              &#8635; retry
            </button>
          </>
        )}
      </AlertDescription>
    </Alert>
  )
}
