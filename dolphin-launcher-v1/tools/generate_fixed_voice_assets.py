#!/usr/bin/env python3
"""Generate or validate Dolphin Launcher V1 fixed Korean voice prompt assets.

Generation is intentionally developer-side only. Azure credentials are read from
AZURE_SPEECH_KEY / AZURE_SPEECH_REGION and are never written to the manifest,
APK, source tree, logs, or generated filenames.

The generator is atomic: all 22 prompts must synthesize and validate before any
res/raw asset or manifest state is changed.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import io
import json
import os
import shutil
import sys
import tempfile
import urllib.error
import urllib.request
import wave
import xml.sax.saxutils as saxutils
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST_PATH = ROOT / "app/src/main/assets/voice_prompt_manifest.json"
RAW_DIR = ROOT / "app/src/main/res/raw"

DEFAULT_VOICE = "ko-KR-SunHiNeural"
OUTPUT_FORMAT = "riff-24khz-16bit-mono-pcm"
PROFILE_ID = "azure-sunhi-ko-kr-24k-pcm-v1"


def load_manifest() -> dict:
    return json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def asset_path(prompt: dict) -> Path:
    return RAW_DIR / f'{prompt["resource"]}.wav'


def validate_wav_bytes(data: bytes, resource: str) -> None:
    if len(data) < 1024:
        raise ValueError(f"{resource}: synthesized WAV is unexpectedly small ({len(data)} bytes)")
    if data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise ValueError(f"{resource}: expected RIFF/WAVE output")
    try:
        with wave.open(io.BytesIO(data), "rb") as stream:
            profile = (
                stream.getnchannels(),
                stream.getsampwidth() * 8,
                stream.getframerate(),
                stream.getcomptype(),
            )
            frame_count = stream.getnframes()
    except (EOFError, wave.Error) as exc:
        raise ValueError(f"{resource}: invalid PCM WAV container: {exc}") from exc
    expected = (1, 16, 24000, "NONE")
    if profile != expected:
        raise ValueError(
            f"{resource}: WAV profile mismatch expected={expected} actual={profile}"
        )
    if frame_count <= 0:
        raise ValueError(f"{resource}: PCM WAV contains no audio frames")


def wav_fixture(sample_rate: int = 24000, channels: int = 1, sample_width: int = 2) -> bytes:
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as stream:
        stream.setnchannels(channels)
        stream.setsampwidth(sample_width)
        stream.setframerate(sample_rate)
        stream.writeframes(bytes(channels * sample_width * 1200))
    return buffer.getvalue()


def wav_profile_self_test() -> None:
    validate_wav_bytes(wav_fixture(), "selftest_valid")
    invalid_profiles = {
        "selftest_rate": wav_fixture(sample_rate=16000),
        "selftest_channels": wav_fixture(channels=2),
        "selftest_width": wav_fixture(sample_width=1),
    }
    for resource, data in invalid_profiles.items():
        try:
            validate_wav_bytes(data, resource)
        except ValueError:
            continue
        raise SystemExit(f"{resource}: invalid WAV profile unexpectedly accepted")
    print("VOICE_WAV_PROFILE_SELF_TEST_OK rate=24000 bits=16 channels=1 pcm=true")


def check_assets(manifest: dict) -> int:
    wav_profile_self_test()
    prompts = manifest.get("prompts", [])
    if len(prompts) != 22:
        raise SystemExit(f"expected 22 prompts, found {len(prompts)}")

    states = {str(p.get("state", "")) for p in prompts}
    allowed = {"ASSET_REQUIRED", "ASSET_READY"}
    unknown = states - allowed
    if unknown:
        raise SystemExit("unsupported voice asset state(s): " + ",".join(sorted(unknown)))
    if len(states) != 1:
        raise SystemExit("mixed fixed-asset states are forbidden; generation must be atomic")

    state = next(iter(states))
    if state == "ASSET_REQUIRED":
        stray = [str(asset_path(p)) for p in prompts if asset_path(p).exists()]
        if stray:
            raise SystemExit(
                "voice assets exist while manifest is ASSET_REQUIRED; regenerate atomically or remove stray files: "
                + ",".join(stray)
            )
        print("VOICE_FIXED_ASSET_STATE state=ASSET_REQUIRED ready=0 pending=22 atomic=true")
        return 0

    for prompt in prompts:
        path = asset_path(prompt)
        if not path.is_file():
            raise SystemExit(f'missing ready asset: {path}')
        data = path.read_bytes()
        validate_wav_bytes(data, prompt["resource"])
        actual = hashlib.sha256(data).hexdigest()
        expected = str(prompt.get("sha256", "")).lower()
        if len(expected) != 64 or actual != expected:
            raise SystemExit(
                f'{prompt["resource"]}: sha256 mismatch expected={expected or "<missing>"} actual={actual}'
            )
        if prompt.get("format") != "wav":
            raise SystemExit(f'{prompt["resource"]}: manifest format must be wav')
        if int(prompt.get("bytes", 0)) != len(data):
            raise SystemExit(f'{prompt["resource"]}: manifest byte count mismatch')

    generation = manifest.get("generation", {})
    if generation.get("profile_id") != PROFILE_ID:
        raise SystemExit("ready assets must use the locked generation profile")
    if generation.get("voice") != manifest.get("preferred_voice", {}).get("voice"):
        raise SystemExit("generation voice must match preferred_voice.voice")
    print("VOICE_FIXED_ASSET_STATE state=ASSET_READY ready=22 pending=0 atomic=true sha256=verified")
    return 0


def synthesize(region: str, key: str, voice: str, phrase: str, timeout: float) -> bytes:
    endpoint = f"https://{region}.tts.speech.microsoft.com/cognitiveservices/v1"
    escaped_voice = saxutils.escape(voice, {'"': "&quot;"})
    escaped_phrase = saxutils.escape(phrase)
    ssml = (
        '<speak version="1.0" xml:lang="ko-KR">'
        f'<voice name="{escaped_voice}">'
        '<prosody rate="-4%" pitch="+0Hz">'
        f"{escaped_phrase}"
        "</prosody></voice></speak>"
    ).encode("utf-8")
    request = urllib.request.Request(
        endpoint,
        data=ssml,
        method="POST",
        headers={
            "Ocp-Apim-Subscription-Key": key,
            "Content-Type": "application/ssml+xml",
            "X-Microsoft-OutputFormat": OUTPUT_FORMAT,
            "User-Agent": "DolphinLauncherVoiceAssetGenerator/1.0",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            data = response.read()
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="ignore")[:300]
        raise RuntimeError(f"speech synthesis failed: HTTP {exc.code} {detail}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"speech synthesis network failure: {exc.reason}") from exc
    return data


def generate(manifest: dict, voice: str, timeout: float) -> int:
    key = os.environ.get("AZURE_SPEECH_KEY", "").strip()
    region = os.environ.get("AZURE_SPEECH_REGION", "").strip()
    if not key or not region:
        raise SystemExit(
            "AZURE_SPEECH_KEY and AZURE_SPEECH_REGION are required for generation; "
            "credentials are intentionally not stored in the repository"
        )

    prompts = manifest.get("prompts", [])
    if len(prompts) != 22:
        raise SystemExit(f"expected 22 prompts, found {len(prompts)}")

    resources = [p.get("resource") for p in prompts]
    if len(set(resources)) != 22 or any(not r or not str(r).startswith("voice_") for r in resources):
        raise SystemExit("voice prompt resources must be 22 unique voice_* names")

    RAW_DIR.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="dolphin-fixed-voice-") as td:
        stage = Path(td)
        staged = []
        for index, prompt in enumerate(prompts, start=1):
            resource = str(prompt["resource"])
            phrase = str(prompt["phrase"])
            data = synthesize(region, key, voice, phrase, timeout)
            validate_wav_bytes(data, resource)
            path = stage / f"{resource}.wav"
            path.write_bytes(data)
            staged.append((prompt, path))
            print(f"VOICE_SYNTHESIZED index={index}/22 resource={resource} bytes={len(data)}")

        # Commit generated files only after every prompt has succeeded.
        for prompt, staged_path in staged:
            destination = asset_path(prompt)
            shutil.copyfile(staged_path, destination)
            prompt["state"] = "ASSET_READY"
            prompt["format"] = "wav"
            prompt["bytes"] = destination.stat().st_size
            prompt["sha256"] = sha256_file(destination)

    preferred = manifest.setdefault("preferred_voice", {})
    preferred.update(
        {
            "language": "ko-KR",
            "gender": "female",
            "voice": voice,
            "provider": "Azure Speech",
            "style": "calm OEM-like",
            "delivery": "pre-generated fixed assets",
            "output_format": OUTPUT_FORMAT,
            "fallback": "Android TextToSpeech; gender not guaranteed",
        }
    )
    manifest["generation"] = {
        "profile_id": PROFILE_ID,
        "provider": "Azure Speech",
        "voice": voice,
        "output_format": OUTPUT_FORMAT,
        "generated_at_utc": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat(),
        "credential_storage": "environment-only; never persisted",
        "atomic_asset_count": 22,
    }
    MANIFEST_PATH.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    check_assets(manifest)
    print("VOICE_FIXED_ASSET_GENERATION_OK assets=22 voice=" + voice)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("check", help="validate pending/ready fixed-asset state without network")

    gen = sub.add_parser("generate", help="synthesize all 22 fixed WAV assets atomically")
    gen.add_argument("--voice", default=DEFAULT_VOICE)
    gen.add_argument("--timeout", type=float, default=30.0)

    args = parser.parse_args()
    manifest = load_manifest()
    if args.command == "check":
        return check_assets(manifest)
    if args.command == "generate":
        return generate(manifest, args.voice, args.timeout)
    raise AssertionError(args.command)


if __name__ == "__main__":
    sys.exit(main())
