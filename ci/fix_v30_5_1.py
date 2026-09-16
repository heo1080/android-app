#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
main_path = ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/MainActivity.kt"
text = main_path.read_text(encoding="utf-8")

count_single = text.count("singleLine = true")
if count_single != 4:
    raise SystemExit(f"expected 4 singleLine assignments, got {count_single}")
text = text.replace("singleLine = true", "setSingleLine(true)")

broken = '''text = "☁ ${upload.message}
세션: ${upload.sessionId} · 파일 ${upload.uploadedFiles}개"'''
fixed = '''text = "☁ ${upload.message}\\n세션: ${upload.sessionId} · 파일 ${upload.uploadedFiles}개"'''
if broken not in text:
    raise SystemExit("generated upload status multiline string not found")
text = text.replace(broken, fixed, 1)

main_path.write_text(text, encoding="utf-8")
print("v30.5.1 generated MainActivity Kotlin syntax fixed")
