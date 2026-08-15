import type { Metadata, Viewport } from 'next'

import './globals.css'

export const metadata: Metadata = {
  title: 'ASTRA — Live Assistant',
  description: 'A low-latency voice surface for the ASTRA personal assistant.',
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
