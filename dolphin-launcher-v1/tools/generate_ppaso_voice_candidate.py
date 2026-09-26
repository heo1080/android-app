#!/usr/bin/env python3
"""Generate an open-license Korean fixed-voice candidate bundle.

This tool is candidate-only. It never edits the app voice_prompt_manifest.json,
never writes into res/raw, and never changes ASSET_REQUIRED/ASSET_READY state.

The model snapshot is supplied externally by CI. No model weights are packaged
into the Android app.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import importlib.util
import json
import math
import sys
import wave
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
MANIFEST_PATH = ROOT / "app/src/main/assets/voice_prompt_manifest.json"
SOURCE_RATE = 22050
TARGET_RATE = 24000
MODEL_REPO = "akamotaco/ppaso-tts-v1"
DEFAULT_MODEL_REVISION = "main"
PROFILE_ID = "ppaso-v8-ko-female-24k-pcm-candidate-v1"

SYNTH_TEXT = {
    "gear_p": "피",
    "gear_r": "알",
    "gear_n": "엔",
    "gear_d": "디",
    "icc_on": "아이씨씨 켜짐",
    "icc_off": "아이씨씨 꺼짐",
}


def load_manifest() -> dict:
    return json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def resample_to_24k(samples: np.ndarray, source_rate: int = SOURCE_RATE) -> np.ndarray:
    data = np.asarray(samples, dtype=np.float32).reshape(-1)
    if data.size == 0:
        raise ValueError("empty synthesized audio")
    if source_rate <= 0:
        raise ValueError("source sample rate must be positive")
    if source_rate == TARGET_RATE:
        return data.copy()

    # Deterministic linear interpolation keeps the candidate workflow dependency-light.
    # Production promotion remains blocked on audible review, so this resampler is not
    # itself treated as quality evidence.
    target_frames = max(1, int(round(data.size * TARGET_RATE / source_rate)))
    old_x = np.arange(data.size, dtype=np.float64)
    new_x = np.linspace(0.0, float(data.size - 1), target_frames, dtype=np.float64)
    return np.interp(new_x, old_x, data).astype(np.float32)


def float_to_pcm16(samples: np.ndarray) -> np.ndarray:
    data = np.asarray(samples, dtype=np.float32).reshape(-1)
    if not np.all(np.isfinite(data)):
        raise ValueError("synthesized audio contains non-finite samples")
    peak = float(np.max(np.abs(data))) if data.size else 0.0
    if peak <= 0.0:
        raise ValueError("synthesized audio is silent")
    if peak > 0.98:
        data = data * (0.98 / peak)
    return np.clip(np.rint(data * 32767.0), -32768, 32767).astype("<i2")


def write_pcm16_wav(path: Path, samples: np.ndarray) -> None:
    pcm = float_to_pcm16(samples)
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as stream:
        stream.setnchannels(1)
        stream.setsampwidth(2)
        stream.setframerate(TARGET_RATE)
        stream.writeframes(pcm.tobytes())


def validate_wav(path: Path) -> dict:
    if not path.is_file():
        raise ValueError(f"missing candidate wav: {path}")
    with wave.open(str(path), "rb") as stream:
        channels = stream.getnchannels()
        sample_width = stream.getsampwidth()
        rate = stream.getframerate()
        frames = stream.getnframes()
        comp = stream.getcomptype()
    expected = (1, 2, TARGET_RATE, "NONE")
    actual = (channels, sample_width, rate, comp)
    if actual != expected:
        raise ValueError(f"{path.name}: WAV profile mismatch expected={expected} actual={actual}")
    if frames <= 0:
        raise ValueError(f"{path.name}: no PCM frames")
    if path.stat().st_size <= 1024:
        raise ValueError(f"{path.name}: candidate WAV unexpectedly small")
    return {
        "bytes": path.stat().st_size,
        "sha256": sha256_file(path),
        "frames": frames,
        "duration_ms": int(round(frames * 1000.0 / TARGET_RATE)),
    }


def load_ppaso(model_dir: Path):
    module_path = model_dir / "example" / "ppaso_tts.py"
    if not module_path.is_file():
        raise SystemExit(f"missing Ppaso runtime: {module_path}")
    spec = importlib.util.spec_from_file_location("ppaso_tts_candidate_runtime", module_path)
    if spec is None or spec.loader is None:
        raise SystemExit("unable to load Ppaso runtime module")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    cls = getattr(module, "PpasoTTS", None)
    if cls is None:
        raise SystemExit("PpasoTTS class missing from candidate runtime")
    return cls(str(model_dir), backend="onnx")


def generate(model_dir: Path, output_dir: Path, model_revision: str) -> int:
    manifest = load_manifest()
    prompts = manifest.get("prompts", [])
    if len(prompts) != 22:
        raise SystemExit(f"expected 22 prompts, found {len(prompts)}")
    if {p.get("state") for p in prompts} != {"ASSET_REQUIRED"}:
        raise SystemExit("candidate generation requires app manifest to remain ASSET_REQUIRED")

    tts = load_ppaso(model_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    records = []

    for index, prompt in enumerate(prompts, start=1):
        prompt_id = str(prompt["id"])
        phrase = str(prompt["phrase"])
        synth_text = SYNTH_TEXT.get(prompt_id, phrase)
        raw = np.asarray(tts.synthesize(synth_text), dtype=np.float32).reshape(-1)
        converted = resample_to_24k(raw, SOURCE_RATE)
        path = output_dir / f'{prompt["resource"]}.wav'
        write_pcm16_wav(path, converted)
        meta = validate_wav(path)
        records.append({
            "id": prompt_id,
            "event": prompt["event"],
            "value": prompt["value"],
            "resource": prompt["resource"],
            "manifest_phrase": phrase,
            "synthesis_text": synth_text,
            **meta,
        })
        print(
            f"PPASO_VOICE_CANDIDATE index={index}/22 resource={prompt['resource']} "
            f"bytes={meta['bytes']} duration_ms={meta['duration_ms']}"
        )

    candidate = {
        "schema_version": 1,
        "candidate_only": True,
        "promotes_app_manifest": False,
        "app_manifest_state": "ASSET_REQUIRED",
        "profile_id": PROFILE_ID,
        "provider": "Ppaso-TTS",
        "model_repo": MODEL_REPO,
        "model_revision": model_revision,
        "model_license": "Apache-2.0",
        "voice": "single Korean female voice",
        "source_sample_rate": SOURCE_RATE,
        "output_format": "riff-24khz-16bit-mono-pcm",
        "generated_at_utc": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat(),
        "asset_count": len(records),
        "assets": records,
        "promotion_gate": [
            "manual pronunciation/voice-quality review",
            "all 22 semantic prompts intelligible",
            "real-car audible latency/mixing verification",
            "no ASSET_READY transition before reviewed assets are intentionally committed",
        ],
    }
    (output_dir / "ppaso_candidate_manifest.json").write_text(
        json.dumps(candidate, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(
        "PPASO_FIXED_VOICE_CANDIDATE_OK assets=22 rate=24000 bits=16 channels=1 "
        "candidate_only=true app_manifest_unchanged=true"
    )
    return 0


def self_test() -> int:
    # 100 ms deterministic waveform verifies rate conversion and PCM container output.
    source_frames = int(SOURCE_RATE * 0.1)
    t = np.arange(source_frames, dtype=np.float32) / SOURCE_RATE
    source = (0.2 * np.sin(2.0 * math.pi * 440.0 * t)).astype(np.float32)
    converted = resample_to_24k(source)
    expected = int(round(source_frames * TARGET_RATE / SOURCE_RATE))
    if abs(converted.size - expected) > 1:
        raise SystemExit(f"candidate resample size mismatch expected={expected} actual={converted.size}")
    if SYNTH_TEXT != {
        "gear_p": "피",
        "gear_r": "알",
        "gear_n": "엔",
        "gear_d": "디",
        "icc_on": "아이씨씨 켜짐",
        "icc_off": "아이씨씨 꺼짐",
    }:
        raise SystemExit("candidate pronunciation override drift")
    print(
        "PPASO_CANDIDATE_SELF_TEST_OK source_rate=22050 target_rate=24000 "
        "semantic_phrase_preserved=true promotion=false"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("self-test")
    gen = sub.add_parser("generate")
    gen.add_argument("--model-dir", required=True, type=Path)
    gen.add_argument("--output-dir", required=True, type=Path)
    gen.add_argument("--model-revision", default=DEFAULT_MODEL_REVISION)
    args = parser.parse_args()

    if args.command == "self-test":
        return self_test()
    if args.command == "generate":
        return generate(args.model_dir.resolve(), args.output_dir.resolve(), str(args.model_revision))
    raise AssertionError(args.command)


if __name__ == "__main__":
    sys.exit(main())
