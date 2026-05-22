import type { Config } from 'tailwindcss'
import animate from 'tailwindcss-animate'

// Design tokens are the source-of-truth in docs/buyer-simulator-ux.md §4.
// Palette: "Precision broadsheet" — warm-black ink, bone text, brass accent,
// semantic emerald/amber/rose for saga state truth.
const config = {
  darkMode: ['class'],
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // shadcn theme variables (driven by CSS custom properties in index.css)
        background: 'hsl(var(--background))',
        foreground: 'hsl(var(--foreground))',
        card: {
          DEFAULT: 'hsl(var(--card))',
          foreground: 'hsl(var(--card-foreground))',
        },
        popover: {
          DEFAULT: 'hsl(var(--popover))',
          foreground: 'hsl(var(--popover-foreground))',
        },
        primary: {
          DEFAULT: 'hsl(var(--primary))',
          foreground: 'hsl(var(--primary-foreground))',
        },
        secondary: {
          DEFAULT: 'hsl(var(--secondary))',
          foreground: 'hsl(var(--secondary-foreground))',
        },
        muted: {
          DEFAULT: 'hsl(var(--muted))',
          foreground: 'hsl(var(--muted-foreground))',
        },
        accent: {
          DEFAULT: 'hsl(var(--accent))',
          foreground: 'hsl(var(--accent-foreground))',
        },
        destructive: {
          DEFAULT: 'hsl(var(--destructive))',
          foreground: 'hsl(var(--destructive-foreground))',
        },
        border: 'hsl(var(--border))',
        input: 'hsl(var(--input))',
        ring: 'hsl(var(--ring))',

        // Raw APG brand palette — use these directly when you need the
        // hex-pinned tone (e.g. `text-apg-bone`, `border-apg-rule`).
        apg: {
          ink: '#0A0A0B',
          surface: '#131316',
          'surface-2': '#1B1B1F',
          rule: '#26262B',
          bone: '#F4F4F2',
          ash: '#7C7C82',
          brass: '#E8C547',
          emerald: '#5DD891',
          amber: '#F5C156',
          rose: '#FF6B7A',
        },
      },
      fontFamily: {
        // Display: editorial serif. Use sparingly for headings, terminal
        // states, and Roman numerals on scenario chips.
        serif: ['"Instrument Serif"', 'serif'],
        // UI body font.
        sans: ['Geist', 'system-ui', 'sans-serif'],
        // Mono for case IDs, amounts, JSON, model IDs, latency/cost.
        mono: ['"Geist Mono"', 'ui-monospace', 'monospace'],
      },
      // Spacing scale honouring the UX doc: wider top jumps than the default
      // 4-8-16-32-64 to enable editorial whitespace at the macro level while
      // keeping micro spacing tight. Custom keys named after the rem value.
      spacing: {
        'apg-1': '0.25rem',
        'apg-2': '0.5rem',
        'apg-3': '0.75rem',
        'apg-4': '1rem',
        'apg-5': '1.5rem',
        'apg-6': '2rem',
        'apg-7': '3rem',
        'apg-8': '5rem',
        'apg-9': '8rem',
      },
      borderRadius: {
        // Hero structural panels render with zero radius — they read as
        // hairline-ruled stubs, not soft cards. Interactive controls get 4px.
        none: '0',
        sm: '4px',
        // shadcn variables map to sm/none so generated components inherit
        // the same restraint.
        DEFAULT: '4px',
        md: '4px',
        lg: '4px',
      },
    },
  },
  plugins: [animate],
} satisfies Config

export default config
