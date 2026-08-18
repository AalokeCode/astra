import type { Metadata, Viewport } from 'next'

import './globals.css'

export const metadata: Metadata = {
  title: 'ASTRA — Personal Intelligence',
  description: 'Voice assistant and visible coding-agent workspace for ASTRA.',
}

export const viewport: Viewport = {
  themeColor: '#080a0d',
  colorScheme: 'dark',
}

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  )
}
