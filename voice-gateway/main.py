import hmac
import os

from flask import Flask, Response, jsonify, request
from google.cloud import texttospeech

app = Flask(__name__)

ALLOWED_VOICES = {
    "ko-KR-Chirp3-HD-Aoede",
    "ko-KR-Chirp3-HD-Kore",
    "ko-KR-Chirp3-HD-Charon",
    "ko-KR-Chirp3-HD-Fenrir",
}
MAX_TEXT_LENGTH = 300


def authorized() -> bool:
    expected = os.environ.get("DOLPHIN_GATEWAY_TOKEN", "")
    supplied = request.headers.get("X-Dolphin-Token", "")
    return bool(expected) and bool(supplied) and hmac.compare_digest(expected, supplied)


@app.get("/health")
def health():
    return jsonify({"ok": True, "service": "dolphin-natural-voice", "voices": sorted(ALLOWED_VOICES)})


@app.post("/synthesize")
def synthesize():
    if not authorized():
        return jsonify({"error": "unauthorized"}), 401

    payload = request.get_json(silent=True) or {}
    text = str(payload.get("text", "")).strip()
    voice_name = str(payload.get("voice", "ko-KR-Chirp3-HD-Aoede")).strip()

    if not text or len(text) > MAX_TEXT_LENGTH:
        return jsonify({"error": f"text must be 1..{MAX_TEXT_LENGTH} characters"}), 400
    if voice_name not in ALLOWED_VOICES:
        return jsonify({"error": "unsupported voice"}), 400

    client = texttospeech.TextToSpeechClient()
    response = client.synthesize_speech(
        input=texttospeech.SynthesisInput(text=text),
        voice=texttospeech.VoiceSelectionParams(
            language_code="ko-KR",
            name=voice_name,
        ),
        audio_config=texttospeech.AudioConfig(
            audio_encoding=texttospeech.AudioEncoding.LINEAR16,
        ),
    )
    return Response(
        response.audio_content,
        status=200,
        content_type="audio/wav",
        headers={"Cache-Control": "no-store"},
    )


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", "8080")))
