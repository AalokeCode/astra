"""Asterisk AudioSocket transport for the Android SIP assistant."""

from __future__ import annotations

import asyncio
import contextlib
import logging
from collections.abc import Mapping
from typing import Any

import aiohttp

from app.agent.agent import Assistant
from app.config import Config
from app.voice.audio import resample_pcm16le
from app.voice.gemini_live import (
    GEMINI_INPUT_SAMPLE_RATE,
    GEMINI_OUTPUT_SAMPLE_RATE,
    GeminiLiveSession,
    LiveQuotaExceeded,
    LiveQuotaGuard,
)

log = logging.getLogger(__name__)

AUDIO_SOCKET_SAMPLE_RATE = 8_000
AUDIO_SOCKET_AUDIO = 0x10
AUDIO_SOCKET_HANGUP = 0x00
AUDIO_SOCKET_UUID = 0x01
AUDIO_SOCKET_ERROR = 0xFF
FRAME_BYTES = 320  # 20 ms of signed-linear 8 kHz mono PCM.
FRAME_DURATION_SECONDS = 0.02


def pack_frame(kind: int, payload: bytes = b"") -> bytes:
    if len(payload) > 0xFFFF:
        raise ValueError("AudioSocket payload is too large")
    return bytes((kind,)) + len(payload).to_bytes(2, "big") + payload


class _AudioSocketSink:
    def __init__(
        self, writer: asyncio.StreamWriter, *, queue_chunks: int
    ) -> None:
        self._writer = writer
        self._queue: asyncio.Queue[bytes | None] = asyncio.Queue(
            maxsize=max(4, queue_chunks)
        )
        self._pending = bytearray()
        self._dropped_frames = 0
        self._write_lock = asyncio.Lock()
        self._player = asyncio.create_task(self._play(), name="audiosocket-playback")

    async def send_audio(self, pcm: bytes) -> None:
        converted = resample_pcm16le(
            pcm, GEMINI_OUTPUT_SAMPLE_RATE, AUDIO_SOCKET_SAMPLE_RATE
        )
        self._pending.extend(converted)
        while len(self._pending) >= FRAME_BYTES:
            frame = bytes(self._pending[:FRAME_BYTES])
            del self._pending[:FRAME_BYTES]
            if self._queue.full():
                with contextlib.suppress(asyncio.QueueEmpty):
                    self._queue.get_nowait()
                    self._dropped_frames += 1
                    if self._dropped_frames == 1 or self._dropped_frames % 50 == 0:
                        log.warning(
                            "SIP playback buffer overflow; dropped %d frame(s)",
                            self._dropped_frames,
                        )
            self._queue.put_nowait(frame)

    async def clear_audio(self) -> None:
        self._pending.clear()
        while not self._queue.empty():
            with contextlib.suppress(asyncio.QueueEmpty):
                self._queue.get_nowait()

    async def send_event(self, event: Mapping[str, Any]) -> None:
        del event

    async def _play(self) -> None:
        loop = asyncio.get_running_loop()
        next_frame_at = loop.time()
        try:
            while True:
                frame = await self._queue.get()
                if frame is None:
                    return
                now = loop.time()
                if now < next_frame_at:
                    await asyncio.sleep(next_frame_at - now)
                elif now - next_frame_at > FRAME_DURATION_SECONDS * 2:
                    # Do not burst stale frames after a scheduler/network stall.
                    next_frame_at = now
                async with self._write_lock:
                    self._writer.write(pack_frame(AUDIO_SOCKET_AUDIO, frame))
                    await self._writer.drain()
                next_frame_at += FRAME_DURATION_SECONDS
        except (ConnectionError, asyncio.CancelledError):
            return

    async def aclose(self) -> None:
        if not self._player.done():
            await self._queue.put(None)
        with contextlib.suppress(asyncio.CancelledError):
            await self._player


class AudioSocketGateway:
    """TCP server consumed by Asterisk's `AudioSocket()` dialplan app."""

    def __init__(
        self,
        cfg: Config,
        assistant: Assistant,
        *,
        host: str = "127.0.0.1",
        port: int = 8090,
        quota: LiveQuotaGuard | None = None,
    ) -> None:
        self._cfg = cfg
        self._assistant = assistant
        self._host = host
        self._port = port
        self._quota = quota or LiveQuotaGuard(cfg)
        self._http: aiohttp.ClientSession | None = None
        self._server: asyncio.Server | None = None
        self._closed = asyncio.Event()

    async def start(self) -> None:
        if not self._cfg.gemini_api_key:
            raise RuntimeError("GEMINI_API_KEY is required for SIP calls")
        self._http = aiohttp.ClientSession()
        try:
            self._server = await asyncio.start_server(
                self._handle_connection, self._host, self._port
            )
        except Exception:
            await self._http.close()
            self._http = None
            raise
        log.info("SIP AudioSocket gateway listening on %s:%d", self._host, self._port)

    async def serve_forever(self) -> None:
        await self._closed.wait()

    async def _handle_connection(
        self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter
    ) -> None:
        peer = writer.get_extra_info("peername")
        source = f"sip:{peer[0] if isinstance(peer, tuple) else 'local'}"
        sink = _AudioSocketSink(
            writer, queue_chunks=self._cfg.live_audio_queue_chunks
        )
        session: GeminiLiveSession | None = None
        assert self._http is not None
        try:
            async with self._quota.lease() as allowed_seconds:
                async with asyncio.timeout(allowed_seconds):
                    session = GeminiLiveSession(
                        self._cfg,
                        self._assistant,
                        self._http,
                        sink,
                        source=source,
                    )
                    await session.connect()
                    await session.greet()
                    while True:
                        header = await reader.readexactly(3)
                        kind = header[0]
                        length = int.from_bytes(header[1:], "big")
                        payload = await reader.readexactly(length) if length else b""
                        if kind == AUDIO_SOCKET_HANGUP:
                            break
                        if kind == AUDIO_SOCKET_AUDIO:
                            audio = resample_pcm16le(
                                payload,
                                AUDIO_SOCKET_SAMPLE_RATE,
                                GEMINI_INPUT_SAMPLE_RATE,
                            )
                            await session.send_audio(
                                audio, sample_rate=GEMINI_INPUT_SAMPLE_RATE
                            )
                        elif kind == AUDIO_SOCKET_ERROR:
                            raise RuntimeError("Asterisk reported an AudioSocket error")
                        elif kind != AUDIO_SOCKET_UUID:
                            log.debug("ignoring AudioSocket frame type %#x", kind)
        except LiveQuotaExceeded as exc:
            log.warning("rejecting %s: %s", source, exc)
        except (asyncio.IncompleteReadError, ConnectionResetError):
            pass
        except TimeoutError:
            log.info("ending %s at configured session limit", source)
        except asyncio.CancelledError:
            raise
        except Exception:
            log.exception("SIP AudioSocket session failed for %s", source)
        finally:
            if session is not None:
                await session.aclose()
            await sink.aclose()
            writer.close()
            with contextlib.suppress(ConnectionError):
                await writer.wait_closed()

    async def aclose(self) -> None:
        self._closed.set()
        if self._server is not None:
            self._server.close()
            await self._server.wait_closed()
        if self._http is not None:
            await self._http.close()
