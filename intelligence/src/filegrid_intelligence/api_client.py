"""HTTP client for the FileGrid api (Spring Boot, context path /api). Failures never raise to callers:
they come back as {"error": ...} so the assistant can say explicitly that a source is unavailable.

Every request carries a bearer token from a ``TokenSource``: the asking user's token (request-scoped,
see ``with_token``) or the ``filegrid-intelligence`` service token for background work. A refreshable
source gets exactly one retry after a 401. Error payloads never contain the token."""

from __future__ import annotations

import logging
from typing import Protocol

import httpx

from .auth import StaticToken

log = logging.getLogger(__name__)


class TokenSource(Protocol):
    refreshable: bool

    def get(self) -> str | None: ...

    def invalidate(self) -> None: ...


class FileGridApi:
    def __init__(self, base_url: str, timeout: float = 8.0, transport: httpx.BaseTransport | None = None,
                 token_source: TokenSource | None = None, client: httpx.Client | None = None):
        self.base_url = base_url.rstrip("/")
        self.client = client or httpx.Client(base_url=self.base_url, timeout=timeout, transport=transport)
        self.token_source = token_source

    def with_token(self, token: str) -> FileGridApi:
        """A request-scoped view acting as the asking user (shares the connection pool, no refresh)."""
        return FileGridApi(self.base_url, token_source=StaticToken(token), client=self.client)

    def _headers(self) -> dict[str, str]:
        token = self.token_source.get() if self.token_source is not None else None
        return {"Authorization": f"Bearer {token}"} if token else {}

    def _request(self, method: str, path: str, **kwargs) -> httpx.Response:
        resp = self.client.request(method, path, headers=self._headers(), **kwargs)
        if resp.status_code == 401 and self.token_source is not None and self.token_source.refreshable:
            self.token_source.invalidate()
            resp = self.client.request(method, path, headers=self._headers(), **kwargs)
        return resp

    def get(self, path: str, params: dict | None = None):
        clean = {k: v for k, v in (params or {}).items() if v not in (None, "")}
        try:
            resp = self._request("GET", path, params=clean)
        except httpx.HTTPError as exc:
            return {"error": f"api indisponibil: {exc.__class__.__name__}", "path": path}
        if resp.status_code >= 400:
            return {"error": f"HTTP {resp.status_code}", "path": path}
        try:
            return resp.json()
        except ValueError:
            return {"error": "răspuns non-JSON", "path": path}

    def post(self, path: str, payload: dict):
        try:
            resp = self._request("POST", path, json=payload)
        except httpx.HTTPError as exc:
            return {"error": f"api indisponibil: {exc.__class__.__name__}", "path": path}
        if resp.status_code >= 400:
            return {"error": f"HTTP {resp.status_code}", "path": path, "body": resp.text[:300]}
        try:
            return resp.json()
        except ValueError:
            return {}

    def create_incident(self, payload: dict) -> str | None:
        """Returns the incident id, or None when the api is unavailable (the caller retries later)."""
        result = self.post("/incidents", payload)
        if isinstance(result, dict) and result.get("error"):
            # 409 = an open incident with the same fingerprint exists: find and reuse it.
            if result["error"] == "HTTP 409":
                existing = self.get("/incidents", {"open": "true", "fingerprint": payload.get("fingerprint")})
                for item in _items(existing):
                    if item.get("fingerprint") == payload.get("fingerprint") and item.get("id"):
                        return str(item["id"])
            log.info("incident not created yet (%s): %s", result.get("error"), payload.get("fingerprint"))
            return None
        if isinstance(result, dict):
            incident_id = result.get("id") or (result.get("incident") or {}).get("id")
            return str(incident_id) if incident_id else None
        return None


def _items(value) -> list[dict]:
    if isinstance(value, list):
        return [v for v in value if isinstance(v, dict)]
    if isinstance(value, dict):
        for key in ("items", "content", "incidents"):
            if isinstance(value.get(key), list):
                return [v for v in value[key] if isinstance(v, dict)]
    return []
