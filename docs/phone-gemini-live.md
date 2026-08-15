# Gemini Live voice deployment

Every voice surface terminates in `GeminiLiveSession`. Gemini handles VAD,
speech understanding, tools, and 24 kHz speech output without a separate STT or
TTS service.

```text
Browser mic ───────────────────────────────┐
Android SIP -> Asterisk -> AudioSocket ────┼─> quota guard -> Gemini Live -> ASTRA tools
Indian PSTN -> Plivo WebSocket ────────────┤
Global PSTN -> Twilio WebSocket ───────────┘
```

## Credentials and budget guard

Rotate any key that has appeared in chat, logs, or source control. Put its
replacement only in `.env`:

```dotenv
GEMINI_API_KEY=...
GEMINI_LIVE_MODEL=gemini-3.1-flash-live-preview
GEMINI_LIVE_VOICE=Aoede

# Safe personal/free-tier defaults shared by every transport.
LIVE_MAX_CONCURRENT_SESSIONS=1
LIVE_MAX_SESSION_SECONDS=600
LIVE_MAX_DAILY_MINUTES=60
LIVE_CONTEXT_TRIGGER_TOKENS=25000
LIVE_CONTEXT_TARGET_TOKENS=8000
LIVE_AUDIO_QUEUE_CHUNKS=500
LIVE_TRANSCRIPTIONS=true

PHONE_PUBLIC_URL=https://your-public-host
PHONE_STREAM_TOKEN=<output of openssl rand -hex 32>
```

These are application limits, not claims about the account's Google quota.
Google applies limits per project and tells you to view the active numbers in
AI Studio. One key does not create another quota pool for the same project.
Set `LIVE_TRANSCRIPTIONS=false` when the visual transcript/history is not worth
the additional text tokens.

The 10-minute cap deliberately ends a connection before Gemini's approximate
connection lifetime. Context compression bounds long-session rebilling. Audio
arrives in 20–40 ms chunks, browser mic input is resampled to native 16 kHz, and
barge-in clears every downstream playback queue. The SIP queue can absorb up to
10 seconds of bursty model output, but playback still begins immediately.

## Browser/Mac visualizer

```bash
.venv/bin/python -m app.main --phone
cd web && npm run dev
```

The UI connects to `/browser/media`. Enter `PHONE_STREAM_TOKEN` in Settings;
it remains in `sessionStorage`. The Gemini API key stays server-side.

## India number through Plivo

Complete Plivo's India business/KYC requirements, rent a voice-enabled number,
expose port 8080 behind HTTPS/WSS, and set the number's Answer URL to:

```text
https://your-public-host/phone/answer?token=<PHONE_STREAM_TOKEN>
```

The generated XML requests bidirectional 24 kHz L16, avoiding a transcoder in
the Gemini output path.

## International number through Twilio

Buy a supported non-Indian number and set its incoming voice webhook to:

```text
https://your-public-host/twilio/answer?token=<PHONE_STREAM_TOKEN>
```

The gateway returns `<Connect><Stream>` and serves `/twilio/media`. Twilio's
fixed μ-law/8 kHz media is decoded for Gemini and Gemini's 24 kHz output is
resampled/encoded on return. Keep the full webhook URL private because its
query token authorizes a live session.

## Local SIP/Android

```bash
.venv/bin/python -m app.main --sip
```

Asterisk extension 700 in `docs/asterisk/extensions.conf` opens
`AudioSocket(...,127.0.0.1:8090)`. The bridge converts 8 kHz signed-linear
frames to Gemini's native 16 kHz input and paces returned 8 kHz frames every
20 ms. Extension 701 remains a non-AI echo test.

## Readiness and failures

`GET /health` exposes model, transports, active sessions, and today's guarded
runtime. It never returns API keys or tokens.

- WebSocket 1008 with “project denied access” is a Google project/account
  entitlement issue, not an audio-format problem.
- 1013 means the configured concurrency or daily budget is exhausted.
- A normal close at 10 minutes is the local session cap; start another session.
- Keep Asterisk/SIP on a trusted LAN unless TLS/SRTP is configured.
