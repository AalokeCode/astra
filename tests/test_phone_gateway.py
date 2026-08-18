"""Phone gateway protocol tests; no network or real WebSockets."""

from __future__ import annotations

import aiohttp
import pytest
from aiohttp import web

from app.voice.gemini_live import decode_live_message
from app.voice.phone_gateway import (
    PHONE_CONTENT_TYPE,
    PHONE_SAMPLE_RATE,
    PhoneGateway,
    _append_transcript,
    build_gemini_setup,
    build_plivo_stream_xml,
    build_twilio_stream_xml,
    _is_allowed_web_origin,
)


def test_gemini_live_decodes_binary_json_frames():
    message = aiohttp.WSMessage(
        aiohttp.WSMsgType.BINARY,
        b'{"setupComplete": {}}\n',
        None,
    )

    assert decode_live_message(message) == {"setupComplete": {}}


def test_plivo_xml_uses_authenticated_bidirectional_24khz_stream():
    xml = build_plivo_stream_xml(
        "https://astra.example.test", "a" * 64
    )

    assert '<Stream bidirectional="true" keepCallAlive="true"' in xml
    assert f'contentType="{PHONE_CONTENT_TYPE};rate={PHONE_SAMPLE_RATE}"' in xml
    assert "wss://astra.example.test/phone/media?token=" + "a" * 64 in xml


def test_plivo_xml_preserves_public_base_path_and_escapes_query_separator():
    xml = build_plivo_stream_xml(
        "https://example.test/astra/", "token with spaces" + "x" * 32
    )

    assert "wss://example.test/astra/phone/media?token=token%20with%20spaces" in xml


def test_plivo_xml_rejects_non_tls_public_url():
    try:
        build_plivo_stream_xml("http://localhost:8080", "x" * 64)
    except ValueError as exc:
        assert "https://" in str(exc)
    else:
        raise AssertionError("non-TLS public URL was accepted")


def test_invalid_pstn_url_does_not_block_local_gateway_construction():
    class Config:
        phone_public_url = "http://127.0.0.1:8080"
        phone_stream_token = "x" * 64

    gateway = PhoneGateway(Config(), object(), quota=object())

    with pytest.raises(web.HTTPServiceUnavailable) as exc_info:
        gateway._require_public_url()
    assert "https://" in exc_info.value.text


def test_transcript_fragments_are_joined_readably():
    text = _append_transcript("Hello", "world")
    text = _append_transcript(text, ", how are you?")
    assert text == "Hello world, how are you?"


def test_gemini_setup_nests_output_options_under_generation_config():
    class Config:
        gemini_live_model = "live-model"
        gemini_live_voice = "Kore"
        live_context_trigger_tokens = 25_000
        live_context_target_tokens = 8_000
        live_transcriptions = True

    class Assistant:
        def live_system_prompt(self):
            return "Be helpful."

        def live_tool_definitions(self):
            return [{"name": "test_tool", "parameters": {"type": "object"}}]

    setup = build_gemini_setup(Config(), Assistant())["setup"]

    assert setup["generationConfig"]["responseModalities"] == ["AUDIO"]
    assert setup["generationConfig"]["speechConfig"]["voiceConfig"] == {
        "prebuiltVoiceConfig": {"voiceName": "Kore"}
    }
    assert "responseModalities" not in setup
    assert setup["contextWindowCompression"] == {
        "triggerTokens": "25000",
        "slidingWindow": {"targetTokens": "8000"},
    }


def test_twilio_xml_uses_authenticated_bidirectional_stream():
    xml = build_twilio_stream_xml("https://astra.example.test", "b" * 64)

    assert "<Connect><Stream" in xml
    assert "wss://astra.example.test/twilio/media?token=" + "b" * 64 in xml


def test_agent_api_accepts_only_local_web_origins():
    assert _is_allowed_web_origin("http://localhost:3000") is True
    assert _is_allowed_web_origin("http://127.0.0.1:3000") is True
    assert _is_allowed_web_origin("") is True
    assert _is_allowed_web_origin("https://attacker.example") is False
    assert _is_allowed_web_origin("http://localhost.attacker.example:3000") is False
