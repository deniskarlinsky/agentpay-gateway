import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

/**
 * Standard shadcn/ui className helper — composes `clsx` (conditional class
 * lists) with `tailwind-merge` (last-wins Tailwind deduplication). Imported
 * from every generated UI component.
 */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}
