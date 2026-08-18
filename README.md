# ASTRA

ASTRA is a Mac-hosted personal assistant with one Gemini Live speech-to-speech
core, a visible coding-agent workspace, and four interchangeable voice transports.

The **Agents** surface can launch Claude Code and Codex in real Kitty tabs,
show their current terminal, explain common failures, interrupt work, and send a
normal follow-up or a steering correction. The same operations are available to
ASTRA's natural-language tool layer, so browser voice commands and the web UI
control the same sessions.

- Browser/Mac visualizer: no telephony charge.
- Android SIP app through local Asterisk AudioSocket: no telephony charge.
- Plivo WebSocket for an Indian public number.
- Twilio Media Stream for an international public number.

The old macOS Speech/STT and `say` pipeline is not used. All voice paths stream
directly to Gemini Live and share one quota guard.

## Start

On the configured Mac, the quickest path is:

```bash
./scripts/astra ip
./scripts/astra start
```

The first command prints the LAN address to enter as the SIP domain in the
Android app. The second starts the local Asterisk server, Gemini gateway, and
web visualizer, then opens `http://localhost:3000`. The phone must be on the
same trusted Wi-Fi/LAN as the Mac; a private LAN address is not reachable over
mobile data without a VPN.

For the Mac web/agent workflow without Asterisk or the SIP bridge:

```bash
./scripts/astra workspace
```

This starts only the HTTP/voice gateway on port 8080 and the Next.js app on
port 3000, then opens the browser. Choose **Agents** to launch a visible Kitty
instance. No prompt or phone call is sent automatically.

```bash
./scripts/astra status
./scripts/astra stop
```

`stop` only terminates processes previously started by this script. Runtime
logs and PID files stay under `~/.astra`, outside the repository.

### First-time setup

Copy `.env.example` to `.env`, create a new Gemini key, and generate the gateway
token with `openssl rand -hex 32`. Never put either credential in the web source.

```bash
python3 -m venv .venv
.venv/bin/pip install -e '.[dev]'
.venv/bin/python -m app.main --serve
```

In a second terminal:

```bash
cd web
npm install
npm run dev
```

Open `http://localhost:3000`, enter
`ws://127.0.0.1:8080/browser/media` and `PHONE_STREAM_TOKEN`, then start a
conversation.

Use `--phone` for browser/PSTN WebSockets only, or `--sip` for AudioSocket only.
`GET http://127.0.0.1:8080/health` reports active quota usage without exposing
credentials.

See [the voice deployment guide](docs/phone-gemini-live.md),
[cost options](docs/voice-options.md), and [Android setup](android/README.md).
See [the agent workspace guide](docs/agent-workspace.md) for Claude Code,
Codex, Kitty, prompting, steering, notifications, MCP, and troubleshooting.
For a complete phone, Mac, credential, and runtime removal procedure, see
[uninstalling ASTRA](docs/uninstall.md).
