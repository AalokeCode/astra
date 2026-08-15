from __future__ import annotations

from array import array
from types import SimpleNamespace

import pytest

from app.voice.audio import mulaw_to_pcm16le, pcm16le_to_mulaw, resample_pcm16le
from app.voice.audio_socket import AUDIO_SOCKET_AUDIO, pack_frame
from app.voice.gemini_live import LiveQuotaExceeded, LiveQuotaGuard


def test_pcm_resampling_preserves_frame_duration():
    source = array("h", range(160)).tobytes()  # 20 ms at 8 kHz

    upsampled = resample_pcm16le(source, 8_000, 16_000)
    downsampled = resample_pcm16le(upsampled, 16_000, 8_000)

    assert len(upsampled) == 640
    assert len(downsampled) == 320


def test_mulaw_round_trip_keeps_speech_scale():
    source = array("h", [-20_000, -1_000, 0, 1_000, 20_000]).tobytes()
    decoded = array("h")
    decoded.frombytes(mulaw_to_pcm16le(pcm16le_to_mulaw(source)))

    assert len(decoded) == 5
    assert decoded[0] < -15_000
    assert abs(decoded[2]) < 200
    assert decoded[-1] > 15_000


def test_audiosocket_frame_has_big_endian_length():
    frame = pack_frame(AUDIO_SOCKET_AUDIO, b"abcd")
    assert frame == b"\x10\x00\x04abcd"


@pytest.mark.asyncio
async def test_live_quota_is_shared_and_persists_session_count(tmp_path):
    cfg = SimpleNamespace(
        data_dir=tmp_path,
        live_max_concurrent_sessions=1,
        live_max_session_seconds=600,
        live_max_daily_minutes=60,
    )
    guard = LiveQuotaGuard(cfg)

    async with guard.lease() as allowed:
        assert allowed == 600
        with pytest.raises(LiveQuotaExceeded, match="slots"):
            async with guard.lease():
                pass

    restored = LiveQuotaGuard(cfg)
    snapshot = await restored.snapshot()
    assert snapshot.sessions_started_today == 1
    assert snapshot.active_sessions == 0
