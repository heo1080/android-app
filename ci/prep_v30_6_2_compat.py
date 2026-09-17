#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
path = ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/service/DolphinService.kt"
text = path.read_text(encoding="utf-8")
needle = "        NaturalVoiceCacheManager.prewarmDefaults(this)\n"
count = text.count(needle)
if count != 1:
    raise SystemExit(f"v30.6.2 prep expected 1 NaturalVoice prewarm hook, got {count}")
text = text.replace(needle, "", 1)
path.write_text(text, encoding="utf-8")
print("v30.6.2 prep: removed obsolete Cloud/Android TTS prewarm hook")
