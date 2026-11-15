"""Voice-note transcription for WhatsApp field reports (Gemini generateContent with inline audio)."""
import base64
import json

import httpx
import pytest

from filegrid_intelligence.voice import GeminiTranscriber, TranscriptionError, make_transcriber


def _transport(seen: list, status: int = 200, text: str = " Avarie la PT-DOB-04-03, fum din transformator. "):
    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        body = {"candidates": [{"content": {"parts": [{"text": text}]}}]}
        return httpx.Response(status, json=body if status == 200 else {"error": {"message": "x"}})
    return httpx.MockTransport(handler)


def test_transcriber_sends_inline_audio_and_returns_text():
    seen: list = []
    t = GeminiTranscriber("k-123", "gemini-test", transport=_transport(seen))
    out = t.transcribe(b"OggS-audio-bytes", "audio/ogg; codecs=opus")
    assert out == "Avarie la PT-DOB-04-03, fum din transformator."
    req = seen[0]
    assert req.url.path.endswith("/models/gemini-test:generateContent")
    assert req.headers["x-goog-api-key"] == "k-123"
    parts = json.loads(req.content)["contents"][0]["parts"]
    inline = next(p["inline_data"] for p in parts if "inline_data" in p)
    assert inline["mime_type"] == "audio/ogg"
    assert base64.b64decode(inline["data"]) == b"OggS-audio-bytes"


def test_transcriber_rejects_non_audio_and_oversized():
    t = GeminiTranscriber("k", "m", transport=_transport([]))
    with pytest.raises(TranscriptionError):
        t.transcribe(b"x", "image/jpeg")
    with pytest.raises(TranscriptionError):
        t.transcribe(b"x" * (GeminiTranscriber.MAX_BYTES + 1), "audio/ogg")


def test_transcriber_error_from_model_is_reported():
    t = GeminiTranscriber("k", "m", transport=_transport([], status=500))
    with pytest.raises(TranscriptionError):
        t.transcribe(b"abc", "audio/ogg")


class _S:
    def __init__(self, **kw):
        self.llm_provider, self.llm_api_key, self.llm_model, self.voice_transcribe_model = "", "", "m", ""
        self.__dict__.update(kw)


def test_make_transcriber_only_for_gemini_with_a_key():
    assert make_transcriber(_S()) is None
    assert make_transcriber(_S(llm_provider="ollama", llm_api_key="k")) is None
    t = make_transcriber(_S(llm_provider="gemini", llm_api_key="k", llm_model="gemini-x"))
    assert isinstance(t, GeminiTranscriber) and t.model == "gemini-x"
    assert make_transcriber(_S(llm_provider="gemini", llm_api_key="k", voice_transcribe_model="tr")).model == "tr"
