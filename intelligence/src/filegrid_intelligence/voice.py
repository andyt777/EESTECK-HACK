"""AI Voice & Field Communication (SIMULATED by default, swappable for a real telephony provider).

- VoiceProvider: interface; SimulatedVoiceProvider produces a script + simulated transcript;
  TwilioVoiceProvider is a stub, selected only when VOICE_PROVIDER=twilio and its env vars are set.
- parse_field_report: turns a field worker's spoken report (browser speech-to-text) into a structured
  incident proposal using deterministic keyword rules.
"""

from __future__ import annotations

import logging
import re
import uuid
from datetime import UTC, datetime
from typing import Protocol

from .docs_index import normalise

log = logging.getLogger(__name__)

ROLE_LABELS = {
    "FIELD_TEAM": "echipa de intervenție",
    "DISPATCHER": "dispecerul de serviciu",
    "SECURITY": "echipa de securitate",
    "MANAGER": "responsabilul de tură",
}


class VoiceProvider(Protocol):
    name: str

    def place_call(self, role: str, script: str) -> tuple[str, str | None]:
        """Returns (status, transcript)."""


class SimulatedVoiceProvider:
    name = "SIMULATED"

    def place_call(self, role: str, script: str) -> tuple[str, str | None]:
        label = ROLE_LABELS.get(role.upper(), role)
        transcript = (f"[SIMULAT] Sistem: {script}\n[SIMULAT] {label.capitalize()}: Am înțeles, confirm primirea. "
                      "Plecăm spre locație și revenim cu un raport din teren.")
        return "COMPLETED", transcript


class TwilioVoiceProvider:
    """Stub for a real voice provider. Not implemented in the hackathon build: it refuses to place calls,
    so the service falls back to the simulated provider (no real phone call is ever made by accident)."""

    name = "TWILIO"

    def __init__(self, account_sid: str, auth_token: str, from_number: str):
        self.account_sid, self.auth_token, self.from_number = account_sid, auth_token, from_number

    def place_call(self, role: str, script: str) -> tuple[str, str | None]:
        raise NotImplementedError("Twilio Voice integration is a stub")


def make_provider(settings) -> VoiceProvider:
    if settings.voice_provider == "twilio" and settings.twilio_account_sid and settings.twilio_auth_token:
        return TwilioVoiceProvider(settings.twilio_account_sid, settings.twilio_auth_token,
                                   settings.twilio_from_number)
    return SimulatedVoiceProvider()


def build_script(incident: dict | None, incident_id: str, role: str, message: str | None) -> str:
    label = ROLE_LABELS.get(role.upper(), role)
    if not incident:
        base = f"Bună ziua, vă sună sistemul FileGrid pentru {label}. Incidentul {incident_id} necesită atenția " \
               "dumneavoastră. Detaliile nu au putut fi încărcate; vă rugăm verificați aplicația."
    else:
        where = ", ".join(x for x in (incident.get("zone"), incident.get("station"), incident.get("asset")) if x)
        certainty = {"SUSPECTED": "suspectat", "REPORTED": "raportat", "CONFIRMED": "confirmat"}.get(
            str(incident.get("certainty")), str(incident.get("certainty") or "nespecificat"))
        base = (f"Bună ziua, vă sună sistemul FileGrid pentru {label}. Incident {incident.get('code') or incident_id}, "
                f"severitate {incident.get('severity', 'necunoscută')}, {certainty}: {incident.get('title')}."
                + (f" Locație: {where}." if where else "")
                + " Vă rugăm confirmați primirea și verificați la fața locului. Nu este o cauză confirmată.")
    if message:
        base += f" Mesaj suplimentar: {message.strip()[:300]}"
    return base


class VoiceService:
    def __init__(self, db, api, provider: VoiceProvider):
        self.db, self.api, self.provider = db, api, provider
        self.fallback = SimulatedVoiceProvider()

    def call(self, incident_id: str, role: str, message: str | None, api=None) -> dict:
        """``api``: request-scoped client acting as the asking user (the incident is read with their rights)."""
        detail = (api or self.api).get(f"/incidents/{incident_id}")
        incident = None
        if isinstance(detail, dict) and not detail.get("error"):
            incident = detail.get("incident", detail)
        script = build_script(incident, incident_id, role, message)
        provider = self.provider
        try:
            status, transcript = provider.place_call(role, script)
        except Exception as exc:  # noqa: BLE001 - never lose the call record
            log.warning("voice provider %s failed (%s), using simulated", provider.name, exc)
            provider = self.fallback
            status, transcript = provider.place_call(role, script)
        record = {"id": str(uuid.uuid4()), "incidentId": incident_id, "role": role.upper(), "provider": provider.name,
                  "script": script, "status": status, "transcript": transcript, "dataOrigin": "SIMULATED",
                  "createdAt": datetime.now(UTC).isoformat().replace("+00:00", "Z")}
        with self.db.meta.connection(timeout=10) as conn:
            conn.execute("INSERT INTO voice_call (id, incident_id, role, provider, script, status, transcript, "
                         "data_origin) VALUES (%s, %s, %s, %s, %s, %s, %s, 'SIMULATED')",
                         (record["id"], incident_id, record["role"], provider.name, script, status, transcript))
        return record

    def list(self, incident_id: str | None) -> list[dict]:
        sql = "SELECT * FROM voice_call"
        params: tuple = ()
        if incident_id:
            sql += " WHERE incident_id = %s"
            params = (incident_id,)
        with self.db.meta.connection(timeout=10) as conn:
            rows = conn.execute(sql + " ORDER BY created_at DESC LIMIT 100", params).fetchall()
        return [{"id": str(r["id"]), "incidentId": r["incident_id"], "role": r["role"], "provider": r["provider"],
                 "script": r["script"], "status": r["status"], "transcript": r["transcript"],
                 "dataOrigin": r["data_origin"], "createdAt": r["created_at"].isoformat()} for r in rows]


# ------------------------------------------------------------------ field reports
TYPE_RULES = [
    ("FIRE_REPORTED", ("incendiu", "foc", "fum", "arde", "flacar")),
    ("FIELD_EMERGENCY", ("electrocut", "ranit", "accident", "victima", "pericol", "urgenta", "cablu cazut",
                         "conductor cazut")),
    ("GRID_FAULT_MAJOR", ("fara curent", "fara tensiune", "intrerupere", "pana de curent", "stalp cazut",
                          "transformator", "scurtcircuit", "avarie", "explozie", "celula")),
    ("GRID_ANOMALY", ("tensiune", "palpaie", "fluctuat", "zgomot", "contor", "anomal")),
]
SEVERE_WORDS = ("victima", "ranit", "electrocut", "explozie", "incendiu", "foc", "cablu cazut", "conductor cazut")
HIGH_WORDS = ("fara curent", "intrerupere", "stalp cazut", "scurtcircuit", "cartier", "mai multe strazi")
STATION_RE = re.compile(r"\b(ST-[A-Z]{3}-\d{2}|PT-[A-Z]{3}-\d{2}-\d{2})\b", re.I)


def parse_field_report(transcript: str, zone: str | None = None, station: str | None = None,
                       topology=None) -> dict:
    text = normalise(transcript)
    kind = next((t for t, words in TYPE_RULES if any(w in text for w in words)), "GRID_ANOMALY")
    if any(w in text for w in SEVERE_WORDS):
        severity = "CRITICAL"
    elif any(w in text for w in HIGH_WORDS) or kind in ("FIRE_REPORTED", "FIELD_EMERGENCY"):
        severity = "HIGH"
    elif kind == "GRID_FAULT_MAJOR":
        severity = "MEDIUM"
    else:
        severity = "LOW"
    asset = None
    match = STATION_RE.search(transcript)
    if match:
        code = match.group(1).upper()
        if code.startswith("PT-"):
            asset = code
            station = station or "ST-" + code[3:9]
        else:
            station = station or code
    if topology is not None and not zone:
        for s in topology.stations:
            if (station and s["id"] == station) or normalise(s["name"]) in text:
                station, zone = s["id"], s.get("zone")
                break
        if not zone:
            zone = next((s.get("zone") for s in topology.stations if normalise(s.get("zone", "")) in text), None)
    clean = re.sub(r"\s+", " ", transcript).strip()
    title_kind = {"FIRE_REPORTED": "Incendiu raportat din teren", "FIELD_EMERGENCY": "Urgență raportată din teren",
                  "GRID_FAULT_MAJOR": "Avarie raportată din teren", "GRID_ANOMALY": "Anomalie raportată din teren"}
    return {
        "type": kind,
        "severity": severity,
        "certainty": "REPORTED",
        "source": "USER",
        "zone": zone,
        "station": station,
        "asset": asset,
        "title": f"{title_kind[kind]}" + (f" – {station}" if station else (f" – {zone}" if zone else "")),
        "description": f"Raport vocal din teren (transcriere automată, neverificată): „{clean[:1500]}”",
        "dataOrigin": "SIMULATED",
        "parser": "keyword-rules",
    }
