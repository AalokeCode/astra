'use client'

import { FormEvent, useEffect, useMemo, useState } from 'react'
import { ArrowUp, AudioLines, Mic, PanelsTopLeft, PhoneOff, Settings2, X } from 'lucide-react'

import AgentWorkspace from '@/components/agent-workspace'
import FluidOrb from '@/components/fluid-orb'
import { AssistantState, useLiveAssistant } from '@/hooks/use-live-assistant'
import { cn } from '@/lib/utils'

const stateCopy: Record<AssistantState, string> = {
  idle: 'Ready when you are',
  connecting: 'Opening a private session',
  listening: 'Listening',
  working: 'Working on it',
  speaking: 'Speaking',
  error: 'Needs attention',
}

const stateColor: Record<AssistantState, string> = {
  idle: '#6f7b89',
  connecting: '#8ea7c9',
  listening: '#1A73F2',
  working: '#6d5dfc',
  speaking: '#36a8ff',
  error: '#e86666',
}

const DEFAULT_GATEWAY_ENDPOINT = 'ws://127.0.0.1:8080/browser/media'

export default function Home() {
  const assistant = useLiveAssistant()
  const [surface, setSurface] = useState<'voice' | 'agents'>('agents')
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [endpoint, setEndpoint] = useState(DEFAULT_GATEWAY_ENDPOINT)
  const [token, setToken] = useState('')
  const [text, setText] = useState('')
  const active = !['idle', 'error'].includes(assistant.state)

  useEffect(() => {
    const restore = window.setTimeout(() => {
      setEndpoint(localStorage.getItem('astra_gateway_endpoint') ?? DEFAULT_GATEWAY_ENDPOINT)
      setToken(sessionStorage.getItem('astra_gateway_token') ?? '')
    }, 0)
    return () => window.clearTimeout(restore)
  }, [])

  const updateEndpoint = (value: string) => {
    setEndpoint(value)
    localStorage.setItem('astra_gateway_endpoint', value)
  }

  const updateToken = (value: string) => {
    setToken(value)
    sessionStorage.setItem('astra_gateway_token', value)
  }

  const transcript = useMemo(() => {
    const completed = assistant.turns.at(-1)
    return {
      user: assistant.partial.user || completed?.input || '',
      assistant: assistant.partial.assistant || completed?.output || '',
    }
  }, [assistant.partial, assistant.turns])

  const toggleSession = async () => {
    if (active) {
      assistant.disconnect()
      return
    }
    setSettingsOpen(false)
    await assistant.connect(endpoint, token)
  }

  const submitText = (event: FormEvent) => {
    event.preventDefault()
    if (assistant.sendText(text)) setText('')
  }

  return (
    <main className={cn('assistant-shell', surface === 'agents' && 'is-agents')}>
      <header className="topbar">
        <div className="brand-lockup">
          <p className="eyebrow">Personal intelligence</p>
          <h1>ASTRA</h1>
        </div>
        <nav className="surface-switcher" aria-label="ASTRA surfaces">
          <button
            className={cn(surface === 'voice' && 'is-selected')}
            type="button"
            onClick={() => setSurface('voice')}
          >
            <AudioLines size={15} /> Voice
          </button>
          <button
            className={cn(surface === 'agents' && 'is-selected')}
            type="button"
            onClick={() => setSurface('agents')}
          >
            <PanelsTopLeft size={15} /> Agents
          </button>
        </nav>
        <div className="topbar-status">
          <span className={cn('gateway-indicator', token.trim() && 'is-configured')} />
          <span>{token.trim() ? 'Gateway set' : 'Not connected'}</span>
          <button
            className="icon-button"
            type="button"
            aria-label={settingsOpen ? 'Close connection settings' : 'Open connection settings'}
            onClick={() => setSettingsOpen((open) => !open)}
          >
            {settingsOpen ? <X size={18} /> : <Settings2 size={18} />}
          </button>
        </div>
      </header>

      {settingsOpen && (
        <section className="settings-panel" aria-label="Connection settings">
          <label>
            Gateway WebSocket
            <input
              value={endpoint}
              onChange={(event) => updateEndpoint(event.target.value)}
              spellCheck={false}
              autoCapitalize="none"
            />
          </label>
          <label>
            Session token
            <input
              type="password"
              value={token}
              onChange={(event) => updateToken(event.target.value)}
              autoComplete="off"
            />
          </label>
          <p>
            The session token stays in this browser tab. Gemini, Claude, Codex, and MCP credentials
            remain on the Mac.
          </p>
        </section>
      )}

      {surface === 'voice' ? (
        <>
          <section className="voice-stage" aria-live="polite">
            <div className={cn('orb-field', `is-${assistant.state}`)}>
              <div className="orb-halo" />
              <FluidOrb size={312} color={stateColor[assistant.state]} />
            </div>
            <div className="state-line">
              <span className={cn('state-dot', `is-${assistant.state}`)} />
              {stateCopy[assistant.state]}
            </div>
            <button
              className={cn('session-button', active && 'is-active')}
              type="button"
              onClick={toggleSession}
              disabled={assistant.state === 'connecting'}
            >
              {active ? <PhoneOff size={19} /> : <Mic size={19} />}
              {active ? 'End conversation' : 'Start conversation'}
            </button>
            {assistant.error && <p className="error-message">{assistant.error}</p>}
          </section>

          <section className="conversation" aria-label="Current conversation">
            {transcript.user || transcript.assistant ? (
              <>
                {transcript.user && (
                  <div className="turn user-turn">
                    <span>You</span>
                    <p>{transcript.user}</p>
                  </div>
                )}
                {transcript.assistant && (
                  <div className="turn assistant-turn">
                    <span>ASTRA</span>
                    <p>{transcript.assistant}</p>
                  </div>
                )}
              </>
            ) : (
              <p className="empty-copy">
                Ask naturally — including “launch Codex in this project and prompt…”
              </p>
            )}
          </section>

          <form className="text-composer" onSubmit={submitText}>
            <label className="sr-only" htmlFor="message">
              Message ASTRA
            </label>
            <input
              id="message"
              value={text}
              onChange={(event) => setText(event.target.value)}
              placeholder={active ? 'Type instead…' : 'Start voice to send a message'}
              disabled={!active}
            />
            <button type="submit" aria-label="Send message" disabled={!active || !text.trim()}>
              <ArrowUp size={18} />
            </button>
          </form>
        </>
      ) : (
        <AgentWorkspace
          endpoint={endpoint}
          token={token}
          onOpenSettings={() => setSettingsOpen(true)}
        />
      )}

      <footer>
        <span>{surface === 'voice' ? 'Gemini Live' : 'Kitty orchestration'}</span>
        <span>{surface === 'voice' ? 'Browser · SIP · PSTN' : 'Claude Code · Codex · native MCP'}</span>
      </footer>
    </main>
  )
}
