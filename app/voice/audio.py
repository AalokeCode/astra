"""Small dependency-free codecs used by real-time voice transports."""

from __future__ import annotations

import sys
from array import array


def _samples(pcm: bytes) -> array[int]:
    values = array("h")
    values.frombytes(pcm[: len(pcm) - (len(pcm) % 2)])
    if sys.byteorder != "little":
        values.byteswap()
    return values


def _pcm(values: array[int]) -> bytes:
    if sys.byteorder != "little":
        values.byteswap()
    return values.tobytes()


def resample_pcm16le(pcm: bytes, source_rate: int, target_rate: int) -> bytes:
    """Linearly resample signed 16-bit little-endian mono PCM.

    Voice frames are only 20-40 ms, so this intentionally favors low latency and
    zero native dependencies over a large high-fidelity DSP package.
    """
    if source_rate <= 0 or target_rate <= 0:
        raise ValueError("sample rates must be positive")
    if source_rate == target_rate:
        return pcm
    source = _samples(pcm)
    if not source:
        return b""
    output_count = max(1, round(len(source) * target_rate / source_rate))
    output = array("h")
    ratio = source_rate / target_rate
    last = len(source) - 1
    for index in range(output_count):
        position = index * ratio
        left = min(int(position), last)
        right = min(left + 1, last)
        fraction = position - left
        value = round(source[left] + (source[right] - source[left]) * fraction)
        output.append(max(-32768, min(32767, value)))
    return _pcm(output)


def _decode_mulaw(value: int) -> int:
    value = (~value) & 0xFF
    sign = value & 0x80
    exponent = (value >> 4) & 0x07
    mantissa = value & 0x0F
    sample = ((mantissa << 3) + 0x84) << exponent
    sample -= 0x84
    return -sample if sign else sample


MULAW_DECODE_TABLE = tuple(_decode_mulaw(value) for value in range(256))


def mulaw_to_pcm16le(data: bytes) -> bytes:
    return _pcm(array("h", (MULAW_DECODE_TABLE[value] for value in data)))


def _encode_mulaw(sample: int) -> int:
    bias = 0x84
    clip = 32635
    sign = 0x80 if sample < 0 else 0
    sample = min(abs(sample), clip) + bias
    exponent = max(0, min(7, sample.bit_length() - 8))
    mantissa = (sample >> (exponent + 3)) & 0x0F
    return (~(sign | (exponent << 4) | mantissa)) & 0xFF


def pcm16le_to_mulaw(pcm: bytes) -> bytes:
    return bytes(_encode_mulaw(sample) for sample in _samples(pcm))
