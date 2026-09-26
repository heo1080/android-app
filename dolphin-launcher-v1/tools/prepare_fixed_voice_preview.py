#!/usr/bin/env python3
"""Prepare a CI-workspace-only fixed voice preview overlay.

The canonical repository manifest must begin with all 22 prompts ASSET_REQUIRED.
This tool validates a Ppaso candidate bundle and then mutates only the checked-out
workspace so a separate voicePreview APK can package the candidate WAVs.

The caller must restore the canonical manifest/resources after the APK is built.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP_MANIFEST = ROOT / "app/src/main/assets/voice_prompt_manifest.json"
RAW_DIR = ROOT / "app/src/main/res/raw"
EXPECTED_PROFILE = "ppaso-v8-ko-female-24k-pcm-candidate-v1"
EXPECTED_MODEL_REVISION = "53d09664c4f636a5fb6f2ebe3ec22cd83ee249b9"
PREVIEW_PACKAGE = "com.dolphin.launcher.v1.voicepreview"
CANONICAL_PACKAGE = "com.dolphin.launcher.v1"


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def validate_wav(path: Path) -> tuple[int, str]:
    data = path.read_bytes()
    if len(data) <= 1024:
        raise SystemExit(f"{path.name}: preview WAV unexpectedly small")
    if data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise SystemExit(f"{path.name}: not RIFF/WAVE")
    try:
        with wave.open(str(path), "rb") as stream:
            actual = (
                stream.getnchannels(),
                stream.getsampwidth(),
                stream.getframerate(),
                stream.getcomptype(),
                stream.getnframes(),
            )
    except wave.Error as exc:
        raise SystemExit(f"{path.name}: invalid WAV: {exc}") from exc
    if actual[:4] != (1, 2, 24000, "NONE") or actual[4] <= 0:
        raise SystemExit(
            f"{path.name}: expected mono/16-bit/24k PCM, actual={actual}"
        )
    return len(data), sha256_bytes(data)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate-dir", required=True, type=Path)
    args = parser.parse_args()

    candidate_dir = args.candidate_dir.resolve()
    candidate_path = candidate_dir / "ppaso_candidate_manifest.json"
    if not candidate_path.is_file():
        raise SystemExit(f"missing candidate manifest: {candidate_path}")

    app = json.loads(APP_MANIFEST.read_text(encoding="utf-8"))
    candidate = json.loads(candidate_path.read_text(encoding="utf-8"))
    prompts = app.get("prompts", [])
    assets = candidate.get("assets", [])

    if len(prompts) != 22:
        raise SystemExit(f"expected 22 canonical prompts, found {len(prompts)}")
    if {p.get("state") for p in prompts} != {"ASSET_REQUIRED"}:
        raise SystemExit("canonical voice manifest must start ASSET_REQUIRED")
    if len(assets) != 22:
        raise SystemExit(f"expected 22 candidate assets, found {len(assets)}")
    if candidate.get("candidate_only") is not True:
        raise SystemExit("candidate bundle is not marked candidate_only")
    if candidate.get("promotes_app_manifest") is not False:
        raise SystemExit("candidate bundle unexpectedly permits canonical promotion")
    if candidate.get("profile_id") != EXPECTED_PROFILE:
        raise SystemExit("candidate profile mismatch")
    if candidate.get("model_revision") != EXPECTED_MODEL_REVISION:
        raise SystemExit("candidate model revision mismatch")
    if candidate.get("output_format") != "riff-24khz-16bit-mono-pcm":
        raise SystemExit("candidate output format mismatch")

    by_id = {str(a.get("id", "")): a for a in assets}
    if len(by_id) != 22:
        raise SystemExit("candidate IDs are missing or duplicated")

    RAW_DIR.mkdir(parents=True, exist_ok=True)
    staged = []
    for prompt in prompts:
        prompt_id = str(prompt["id"])
        resource = str(prompt["resource"])
        asset = by_id.get(prompt_id)
        if asset is None:
            raise SystemExit(f"candidate missing prompt {prompt_id}")
        if str(asset.get("resource")) != resource:
            raise SystemExit(f"{prompt_id}: resource mismatch")
        if str(asset.get("manifest_phrase")) != str(prompt.get("phrase")):
            raise SystemExit(f"{prompt_id}: semantic phrase mismatch")
        if str(asset.get("event")) != str(prompt.get("event")):
            raise SystemExit(f"{prompt_id}: event mismatch")
        if str(asset.get("value")) != str(prompt.get("value")):
            raise SystemExit(f"{prompt_id}: value mismatch")

        src = candidate_dir / f"{resource}.wav"
        if not src.is_file():
            raise SystemExit(f"candidate WAV missing: {src}")
        byte_count, digest = validate_wav(src)
        if byte_count != int(asset.get("bytes", 0)):
            raise SystemExit(f"{resource}: candidate byte-count mismatch")
        if digest != str(asset.get("sha256", "")).lower():
            raise SystemExit(f"{resource}: candidate SHA-256 mismatch")

        dst = RAW_DIR / f"{resource}.wav"
        if dst.exists():
            raise SystemExit(
                f"preview overlay refuses pre-existing packaged asset: {dst}"
            )
        shutil.copyfile(src, dst)
        staged.append(dst)

        prompt["state"] = "ASSET_READY"
        prompt["format"] = "wav"
        prompt["sha256"] = digest
        prompt["bytes"] = byte_count
        prompt["preview_only"] = True

    preferred = dict(app.get("preferred_voice", {}))
    preferred.update(
        {
            "language": "ko-KR",
            "gender": "female",
            "voice": "Ppaso-TTS v1 candidate",
            "provider": "Ppaso-TTS",
            "style": "real-car review candidate",
            "delivery": "voicePreview packaged assets",
            "output_format": "riff-24khz-16bit-mono-pcm",
            "preview_only": True,
        }
    )
    app["preferred_voice"] = preferred
    app["generation_policy"] = {
        "generator": "dolphin-launcher-v1/tools/generate_ppaso_voice_candidate.py",
        "preview_preparer": "dolphin-launcher-v1/tools/prepare_fixed_voice_preview.py",
        "profile_id": EXPECTED_PROFILE,
        "model_revision": EXPECTED_MODEL_REVISION,
        "atomic_asset_count": 22,
        "credentials": "none",
        "build_time_network_generation": True,
        "ready_state_requires_sha256": True,
        "preview_only": True,
    }
    app["preview_build"] = {
        "enabled": True,
        "canonical_package": CANONICAL_PACKAGE,
        "preview_package": PREVIEW_PACKAGE,
        "canonical_repository_state": "ASSET_REQUIRED",
        "workspace_state": "ASSET_READY",
        "promotion_allowed": False,
    }

    APP_MANIFEST.write_text(
        json.dumps(app, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(
        "VOICE_PREVIEW_OVERLAY_READY "
        f"preview_package={PREVIEW_PACKAGE} preview_assets={len(staged)} "
        "workspace_manifest=ASSET_READY canonical_repository_state=ASSET_REQUIRED "
        "promotion_allowed=false"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
