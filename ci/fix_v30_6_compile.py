#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ui_path = ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/manager/IntegratedBetaUi.kt"
text = ui_path.read_text(encoding="utf-8")

needle = "singleLine = true"
count = text.count(needle)
if count != 2:
    raise SystemExit(f"v30.6 UI singleLine fix expected 2 matches, got {count}")
text = text.replace(needle, "setSingleLine(true)")
ui_path.write_text(text, encoding="utf-8")
print("v30.6 compile compatibility fix applied: EditText.setSingleLine(true) x2")
