#!/usr/bin/env python
"""Erzeugt die soxr-Referenz-Fixture fuer ResamplerSoxrParityTest.

Die Golden-Session-WAVs sind bereits 16 kHz mono — der soxr-Pfad von
`preprocess_audio` wird dort nie betreten. Diese Fixture haelt deshalb den
echten soxr-Output (HQ-Default von python-soxr 1.1.0) fuer die einzige
44,1-kHz-Stereo-Testdatei fest, damit der Kotlin-Resampler dagegen
verglichen werden kann: Sample-Slices, Chunk-RMS und Silero-VAD-Probs
des resampelten Signals.

Aufruf (siqas-venv, NUR LESEND auf siqas):
    .../siqas/.venv/bin/python generate_soxr_reference.py
"""
from __future__ import annotations

import json
import os
import sys

SIQAS_ROOT = "/Users/paraaafifi/Developer/work/arbeit_fzi/code/the_App_T/siqas"
sys.path.insert(0, os.path.join(SIQAS_ROOT, "src"))

import numpy as np  # noqa: E402

from library.pipeline.steps.audio_processing import (  # noqa: E402
    load_audio_file,
    preprocess_audio,
)
from library.pipeline.steps import vad as vad_module  # noqa: E402

WAV = os.path.join(SIQAS_ROOT, "assets", "audio", "test_stereo_44100hz_10s.wav")
OUT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "resources", "speakerid", "soxr_reference_test_stereo_44100hz_10s.json",
)
SAMPLE_RATE = 16000
CHUNK = 16000
SLICES = {0: 4000, 80000: 4000}  # start -> laenge


def vad_window_probs(audio_1d: np.ndarray, window_size_samples: int = 256) -> list[float]:
    """Identisch zu golden/generate_golden.py::vad_window_probs."""
    session = vad_module._get_session()
    audio_np = np.squeeze(audio_1d).astype(np.float32)
    remainder = len(audio_np) % window_size_samples
    if remainder != 0:
        audio_np = np.pad(audio_np, (0, window_size_samples - remainder), "constant")
    state = np.zeros((2, 1, 128), dtype=np.float32)
    sr_tensor = np.array(SAMPLE_RATE, dtype=np.int64)
    probs = []
    for i in range(0, len(audio_np), window_size_samples):
        win = np.expand_dims(audio_np[i: i + window_size_samples], axis=0)
        out, state = session.run(None, {"input": win, "state": state, "sr": sr_tensor})
        probs.append(float(out.item()))
    return probs


def main() -> None:
    raw, sr = load_audio_file(WAV)
    assert sr == 44100 and raw.shape[0] == 2, (sr, raw.shape)

    # Zwischenstand vor dem Resampling (Mono-Mix + Peak-Norm bei 44,1 kHz),
    # um WavReader/Preprocessing getrennt vom Resampler zu verifizieren.
    mono = np.mean(raw.astype(np.float32), axis=0, keepdims=True)
    peak = float(np.max(np.abs(mono)))
    mono_norm = (mono / peak).astype(np.float32)

    out16 = preprocess_audio(raw, sr, SAMPLE_RATE)  # kompletter siqas-Pfad inkl. soxr
    audio = np.squeeze(out16).astype(np.float32)

    chunks = [audio[s: s + CHUNK] for s in range(0, len(audio), CHUNK)]
    chunk_rms = [float(np.sqrt(np.mean(c.astype(np.float64) ** 2))) for c in chunks]

    vad_probs = []
    decisions = []
    durations = []
    for c in chunks:
        c_padded = c if len(c) == CHUNK else np.pad(c, (0, CHUNK - len(c)))
        vad_probs.append(vad_window_probs(c_padded))
        is_sil, dur = vad_module.detect_silence(
            c_padded, use_vad=True, min_speech_seconds=0.15, chunk_duration=1.0
        )
        decisions.append(bool(is_sil))
        durations.append(float(dur))

    fixture = {
        "wav": os.path.basename(WAV),
        "input_rate": 44100,
        "output_rate": SAMPLE_RATE,
        "input_len": int(raw.shape[1]),
        "peak_max_abs_raw_mono": peak,
        "preproc44_head": [float(x) for x in mono_norm[0, :32]],
        "output_len": int(len(audio)),
        "sample_slices": {
            str(s): [float(x) for x in audio[s: s + n]] for s, n in SLICES.items()
        },
        "chunk_rms": chunk_rms,
        "vad": {
            "window_probs": vad_probs,
            "is_silence": decisions,
            "speech_duration_s": durations,
        },
        "versions": {"numpy": np.__version__},
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(os.path.normpath(OUT), "w") as f:
        json.dump(fixture, f, indent=1)
    print(f"[soxr-ref] {len(audio)} Samples, {len(chunks)} Chunks -> {os.path.normpath(OUT)}")


if __name__ == "__main__":
    main()
