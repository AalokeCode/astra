"""HTTP/WebSocket transports for browser, Plivo India, and Twilio global calls."""

from __future__ import annotations

import asyncio
import base64
import contextlib
import hmac
import json
import logging
from collections.abc import Mapping
from dataclasses import asdict
from typing import Any
from urllib.parse import quote, urlsplit, urlunsplit
from xml.sax.saxutils import escape

import aiohttp
from aiohttp import web

from app.agent.agent import Assistant
from app.config import Config
from app.voice.audio import mulaw_to_pcm16le, pcm16le_to_mulaw, resample_pcm16le
from app.voice.gemini_live import (
    GEMINI_OUTPUT_SAMPLE_RATE,
    GeminiLiveSession,
    LiveQuotaExceeded,
    LiveQuotaGuard,
    append_transcript as _append_transcript,
    build_gemini_setup,
)

log = logging.getLogger(__name__)

PHONE_SAMPLE_RATE = 24_000
PHONE_CONTENT_TYPE = "audio/x-l16"
PHONE_STREAM_CONTENT_TYPE = f"{PHONE_CONTENT_TYPE};rate={PHONE_SAMPLE_RATE}"
TWILIO_SAMPLE_RATE = 8_000


def _stream_url(public_url: str, path: str, token: str) -> str:
    parsed = urlsplit(public_url)
    if parsed.scheme != "https" or not parsed.netloc:
        raise ValueError("PHONE_PUBLIC_URL must be an https:// origin or base URL")
    base_path = parsed.path.rstrip("/")
    return urlunsplit(
        ("wss", parsed.netloc, f"{base_path}{path}", f"token={quote(token, safe='')}", "")
    )


def _media_url(public_url: str, token: str) -> str:
    return _stream_url(public_url, "/phone/media", token)


def build_plivo_stream_xml(public_url: str, token: str) -> str:
    media_url = escape(_media_url(public_url, token), {'"': "&quot;"})
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        "<Response>"
        f'<Stream bidirectional="true" keepCallAlive="true" '
        f'contentType="{PHONE_STREAM_CONTENT_TYPE}">{media_url}</Stream>'
        "</Response>"
    )


def build_twilio_stream_xml(public_url: str, token: str) -> str:
    media_url = escape(
        _stream_url(public_url, "/twilio/media", token), {'"': "&quot;"}
    )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        f'<Response><Connect><Stream url="{media_url}" /></Connect></Response>'
    )


class _WebSocketSink:
    def __init__(self, socket: web.WebSocketResponse) -> None:
        self.socket = socket
        self._lock = asyncio.Lock()

    async def _json(self, payload: Mapping[str, Any]) -> None:
        if not self.socket.closed:
            async with self._lock:
                await self.socket.send_json(payload)


class _PlivoSink(_WebSocketSink):
    def __init__(self, socket: web.WebSocketResponse) -> None:
        super().__init__(socket)
        self.stream_id = ""

    async def send_audio(self, pcm: bytes) -> None:
        await self._json(
            {
                "event": "playAudio",
                "media": {
                    "contentType": PHONE_CONTENT_TYPE,
                    "sampleRate": PHONE_SAMPLE_RATE,
                    "payload": base64.b64encode(pcm).decode("ascii"),
                },
            }
        )

    async def clear_audio(self) -> None:
        await self._json({"event": "clearAudio", "streamId": self.stream_id})

    async def send_event(self, event: Mapping[str, Any]) -> None:
        del event


class _TwilioSink(_WebSocketSink):
    def __init__(self, socket: web.WebSocketResponse) -> None:
        super().__init__(socket)
        self.stream_id = ""

    async def send_audio(self, pcm: bytes) -> None:
        pcm_8khz = resample_pcm16le(pcm, GEMINI_OUTPUT_SAMPLE_RATE, TWILIO_SAMPLE_RATE)
        await self._json(
            {
                "event": "media",
                "streamSid": self.stream_id,
                "media": {
                    "payload": base64.b64encode(pcm16le_to_mulaw(pcm_8khz)).decode(
                        "ascii"
                    )
                },
            }
        )

    async def clear_audio(self) -> None:
        await self._json({"event": "clear", "streamSid": self.stream_id})

    async def send_event(self, event: Mapping[str, Any]) -> None:
        del event


class _BrowserSink(_WebSocketSink):
    async def send_audio(self, pcm: bytes) -> None:
        if not self.socket.closed:
            async with self._lock:
                await self.socket.send_bytes(pcm)

    async def clear_audio(self) -> None:
        await self._json({"type": "clear"})

    async def send_event(self, event: Mapping[str, Any]) -> None:
        await self._json(event)


class PhoneGateway:
    """Unified low-latency HTTP gateway; the API key never reaches a client."""

    def __init__(
        self,
        cfg: Config,
        assistant: Assistant,
        *,
        host: str = "127.0.0.1",
        port: int = 8080,
        quota: LiveQuotaGuard | None = None,
    ) -> None:
        self._cfg = cfg
        self._assistant = assistant
        self._host = host
        self._port = port
        self._quota = quota or LiveQuotaGuard(cfg)
        self._http: aiohttp.ClientSession | None = None
        self._runner: web.AppRunner | None = None
        self._site: web.TCPSite | None = None
        self._closed = asyncio.Event()

    async def start(self) -> None:
        if not self._cfg.gemini_api_key:
            raise RuntimeError("GEMINI_API_KEY is required for live voice")
        if len(self._cfg.phone_stream_token) < 32:
            raise RuntimeError("PHONE_STREAM_TOKEN must be at least 32 characters")

        self._http = aiohttp.ClientSession()
        app = web.Application(client_max_size=2 * 1024 * 1024)
        app.router.add_route("*", "/phone/answer", self._plivo_answer)
        app.router.add_get("/phone/media", self._plivo_media)
        app.router.add_route("*", "/twilio/answer", self._twilio_answer)
        app.router.add_get("/twilio/media", self._twilio_media)
        app.router.add_get("/browser/media", self._browser_media)
        app.router.add_get("/health", self._health)
        self._runner = web.AppRunner(app, access_log=None)
        try:
            await self._runner.setup()
            self._site = web.TCPSite(self._runner, self._host, self._port)
            await self._site.start()
        except Exception:
            await self._runner.cleanup()
            await self._http.close()
            self._runner = None
            self._http = None
            raise
        log.info("live HTTP gateway listening on %s:%d", self._host, self._port)

    async def serve_forever(self) -> None:
        await self._closed.wait()

    def _require_public_url(self) -> str:
        if not self._cfg.phone_public_url:
            raise web.HTTPServiceUnavailable(
                text="PHONE_PUBLIC_URL is required for PSTN answer routes"
            )
        try:
            _stream_url(
                self._cfg.phone_public_url,
                "/phone/media",
                self._cfg.phone_stream_token,
            )
        except ValueError as exc:
            raise web.HTTPServiceUnavailable(text=str(exc)) from exc
        return self._cfg.phone_public_url

    async def _plivo_answer(self, request: web.Request) -> web.Response:
        self._require_stream_token(request)
        xml = build_plivo_stream_xml(
            self._require_public_url(), self._cfg.phone_stream_token
        )
        return web.Response(text=xml, content_type="application/xml")

    async def _twilio_answer(self, request: web.Request) -> web.Response:
        self._require_stream_token(request)
        xml = build_twilio_stream_xml(
            self._require_public_url(), self._cfg.phone_stream_token
        )
        return web.Response(text=xml, content_type="application/xml")

    async def _health(self, request: web.Request) -> web.Response:
        del request
        return web.json_response(
            {
                "status": "ok",
                "model": self._cfg.gemini_live_model,
                "quota": asdict(await self._quota.snapshot()),
                "transports": ["browser", "plivo", "twilio", "sip-audiosocket"],
            }
        )

    async def _plivo_media(self, request: web.Request) -> web.StreamResponse:
        self._require_stream_token(request)
        socket = web.WebSocketResponse(heartbeat=20, max_msg_size=4 * 1024 * 1024)
        await socket.prepare(request)
        sink = _PlivoSink(socket)
        await self._run_json_call(socket, sink, provider="plivo")
        return socket

    async def _twilio_media(self, request: web.Request) -> web.StreamResponse:
        self._require_stream_token(request)
        socket = web.WebSocketResponse(heartbeat=20, max_msg_size=4 * 1024 * 1024)
        await socket.prepare(request)
        sink = _TwilioSink(socket)
        await self._run_json_call(socket, sink, provider="twilio")
        return socket

    async def _run_json_call(
        self,
        socket: web.WebSocketResponse,
        sink: _PlivoSink | _TwilioSink,
        *,
        provider: str,
    ) -> None:
        assert self._http is not None
        session: GeminiLiveSession | None = None
        try:
            async with self._quota.lease() as allowed_seconds:
                async with asyncio.timeout(allowed_seconds):
                    session = GeminiLiveSession(
                        self._cfg,
                        self._assistant,
                        self._http,
                        sink,
                        source=f"phone:{provider}",
                    )
                    await session.connect()
                    async for message in socket:
                        if message.type is web.WSMsgType.ERROR:
                            raise socket.exception() or RuntimeError("caller socket failed")
                        if message.type is not web.WSMsgType.TEXT:
                            continue
                        event = json.loads(message.data)
                        event_type = event.get("event")
                        if event_type == "start":
                            start = event.get("start") or {}
                            sink.stream_id = str(
                                start.get("streamId") or start.get("streamSid") or ""
                            )
                            await session.greet()
                        elif event_type == "media":
                            encoded = str((event.get("media") or {}).get("payload") or "")
                            if not encoded:
                                continue
                            audio = base64.b64decode(encoded, validate=True)
                            if provider == "twilio":
                                audio = mulaw_to_pcm16le(audio)
                                await session.send_audio(audio, sample_rate=TWILIO_SAMPLE_RATE)
                            else:
                                await session.send_audio(audio, sample_rate=PHONE_SAMPLE_RATE)
                        elif event_type == "stop":
                            break
        except LiveQuotaExceeded as exc:
            await socket.close(code=1013, message=str(exc).encode()[:120])
        except TimeoutError:
            await socket.close(code=1000, message=b"configured session limit reached")
        except asyncio.CancelledError:
            raise
        except Exception:
            log.exception("%s media session failed", provider)
            if not socket.closed:
                await socket.close(code=1011, message=b"Voice session failed")
        finally:
            if session is not None:
                await session.aclose()

    async def _browser_media(self, request: web.Request) -> web.StreamResponse:
        self._require_stream_token(request)
        socket = web.WebSocketResponse(heartbeat=20, max_msg_size=2 * 1024 * 1024)
        await socket.prepare(request)
        sink = _BrowserSink(socket)
        assert self._http is not None
        session: GeminiLiveSession | None = None
        try:
            async with self._quota.lease() as allowed_seconds:
                async with asyncio.timeout(allowed_seconds):
                    session = GeminiLiveSession(
                        self._cfg,
                        self._assistant,
                        self._http,
                        sink,
                        source="browser",
                    )
                    await session.connect()
                    await session.greet()
                    async for message in socket:
                        if message.type is web.WSMsgType.BINARY:
                            await session.send_audio(
                                bytes(message.data), sample_rate=16_000
                            )
                        elif message.type is web.WSMsgType.TEXT:
                            payload = json.loads(message.data)
                            if payload.get("type") == "text":
                                await session.send_text(str(payload.get("text") or ""))
                        elif message.type is web.WSMsgType.ERROR:
                            raise socket.exception() or RuntimeError("browser socket failed")
        except LiveQuotaExceeded as exc:
            await sink.send_event({"type": "error", "message": str(exc)})
            await socket.close(code=1013)
        except TimeoutError:
            await sink.send_event(
                {"type": "error", "message": "Configured session limit reached"}
            )
            await socket.close(code=1000)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            log.exception("browser live session failed")
            with contextlib.suppress(Exception):
                await sink.send_event({"type": "error", "message": str(exc)[:200]})
            if not socket.closed:
                await socket.close(code=1011)
        finally:
            if session is not None:
                await session.aclose()
        return socket

    def _require_stream_token(self, request: web.Request) -> None:
        supplied = request.query.get("token", "")
        if not hmac.compare_digest(supplied, self._cfg.phone_stream_token):
            raise web.HTTPUnauthorized()

    async def aclose(self) -> None:
        self._closed.set()
        if self._runner is not None:
            await self._runner.cleanup()
        if self._http is not None:
            await self._http.close()


__all__ = [
    "PHONE_CONTENT_TYPE",
    "PHONE_SAMPLE_RATE",
    "PhoneGateway",
    "_append_transcript",
    "build_gemini_setup",
    "build_plivo_stream_xml",
    "build_twilio_stream_xml",
]
