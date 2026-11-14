"""Authentication and authorization for /api/intelligence (Stage 11, docs/AUTH-DESIGN.md §3-4).

Inbound: the caller's bearer token is forwarded to ``GET {api}/me``; the api verifies the JWT (signature,
issuer, audience) and returns ``{"actor", "roles", "permissions"}``. The answer is cached per
sha256(token) until the token's ``exp``. Fail-closed: missing/invalid token -> 401, missing permission
-> 403, api unreachable -> 503.

Outbound (background work): ``ClientCredentialsToken`` fetches and caches a ``filegrid-intelligence``
service token (client_credentials), refreshed before expiry.

Tokens never appear in logs, exceptions, reprs or anything that reaches an LLM.
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import json
import logging
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Annotated

import httpx
from fastapi import Depends, HTTPException, Request

log = logging.getLogger(__name__)

MAX_CACHE_ENTRIES = 1000
# Upper bound for a cache entry even if a token claims a far-away exp (Keycloak issues 300 s tokens).
MAX_CACHE_SECONDS = 600.0


@dataclass(frozen=True)
class Principal:
    actor: str
    roles: tuple[str, ...]
    permissions: frozenset[str]
    # The raw bearer token, kept only to call the api on the user's behalf. Excluded from repr/eq.
    token: str = field(repr=False, compare=False)

    def can(self, permission: str) -> bool:
        return permission in self.permissions


def token_exp(token: str) -> float | None:
    """Reads ``exp`` from a JWT payload WITHOUT verifying it (the api verifies). None if unreadable."""
    parts = token.split(".")
    if len(parts) != 3:
        return None
    payload = parts[1] + "=" * (-len(parts[1]) % 4)
    try:
        claims = json.loads(base64.urlsafe_b64decode(payload))
    except (binascii.Error, ValueError, UnicodeDecodeError):
        return None
    exp = claims.get("exp") if isinstance(claims, dict) else None
    return float(exp) if isinstance(exp, int | float) and not isinstance(exp, bool) else None


class AuthUnavailable(Exception):
    """The api could not be asked (network error, timeout, 5xx)."""


class Authenticator:
    """Resolves a bearer token to a Principal through ``GET {api_url}/me``, with a per-token cache."""

    def __init__(self, api_url: str, timeout: float = 5.0, transport: httpx.BaseTransport | None = None,
                 clock: Callable[[], float] = time.time):
        self.me_url = api_url.rstrip("/") + "/me"
        self.http = httpx.Client(timeout=timeout, transport=transport)
        self.clock = clock
        self._cache: dict[str, tuple[Principal, float]] = {}
        self._lock = threading.Lock()

    def authenticate(self, token: str) -> Principal | None:
        """Principal for a valid token, None for an invalid one. Raises AuthUnavailable if the api is down."""
        now = self.clock()
        exp = token_exp(token)
        if exp is not None and exp <= now:
            return None
        key = hashlib.sha256(token.encode()).hexdigest()
        with self._lock:
            hit = self._cache.get(key)
            if hit is not None and hit[1] > now:
                return hit[0]
        try:
            resp = self.http.get(self.me_url, headers={"Authorization": f"Bearer {token}"})
        except httpx.HTTPError as exc:
            raise AuthUnavailable(exc.__class__.__name__) from None
        if resp.status_code in (401, 403):
            return None
        if resp.status_code != 200:
            raise AuthUnavailable(f"HTTP {resp.status_code}")
        try:
            body = resp.json()
            principal = Principal(actor=str(body["actor"]), roles=tuple(str(r) for r in body.get("roles") or ()),
                                  permissions=frozenset(str(p) for p in body.get("permissions") or ()), token=token)
        except (ValueError, KeyError, TypeError):
            raise AuthUnavailable("malformed /me response") from None
        if exp is not None:
            with self._lock:
                if len(self._cache) >= MAX_CACHE_ENTRIES:
                    self._cache = {k: v for k, v in self._cache.items() if v[1] > now}
                    if len(self._cache) >= MAX_CACHE_ENTRIES:
                        self._cache.clear()
                self._cache[key] = (principal, min(exp, now + MAX_CACHE_SECONDS))
        return principal


def _unauthorized() -> HTTPException:
    return HTTPException(401, "Autentificare necesară.", headers={"WWW-Authenticate": "Bearer"})


def bearer_token(request: Request) -> str | None:
    header = request.headers.get("authorization", "")
    scheme, _, value = header.partition(" ")
    value = value.strip()
    if scheme.lower() != "bearer" or not value:
        return None
    return value


_authenticator: Authenticator | None = None


def configure(authenticator: Authenticator) -> None:
    global _authenticator
    _authenticator = authenticator


def get_authenticator() -> Authenticator:
    """FastAPI dependency (overridable in tests). Not configured -> 503, never open."""
    if _authenticator is None:
        raise HTTPException(503, "Serviciul de autorizare nu este disponibil.")
    return _authenticator


def current_principal(request: Request,
                      authenticator: Annotated[Authenticator, Depends(get_authenticator)]) -> Principal:
    token = bearer_token(request)
    if token is None:
        raise _unauthorized()
    try:
        principal = authenticator.authenticate(token)
    except AuthUnavailable as exc:
        log.warning("authorization check unavailable: %s", exc)
        raise HTTPException(503, "Serviciul de autorizare nu este disponibil.") from None
    if principal is None:
        raise _unauthorized()
    return principal


def require(permission: str) -> Callable[..., Principal]:
    """Dependency factory: the authenticated Principal, or 401/403/503 (fail-closed)."""

    def dependency(principal: Annotated[Principal, Depends(current_principal)]) -> Principal:
        if not principal.can(permission):
            log.info("access denied: actor=%s permission=%s", principal.actor, permission)
            raise HTTPException(403, f"Nu aveți permisiunea necesară ({permission}).")
        return principal

    return dependency


class ClientCredentialsToken:
    """OAuth2 client_credentials token, cached and refreshed ``refresh_margin`` seconds before expiry.

    ``get()`` returns None when no client secret is configured (the api will answer 401 and the caller
    reports the source as unavailable). Thread-safe: the Agent 02 loop runs in its own thread.
    """

    refreshable = True

    def __init__(self, token_url: str, client_id: str, client_secret: str, timeout: float = 10.0,
                 refresh_margin: float = 30.0, transport: httpx.BaseTransport | None = None,
                 clock: Callable[[], float] = time.monotonic):
        self.token_url = token_url
        self.client_id = client_id
        self._secret = client_secret
        self.refresh_margin = refresh_margin
        self.clock = clock
        self.http = httpx.Client(timeout=timeout, transport=transport)
        self._token: str | None = None
        self._expires_at = 0.0
        self._lock = threading.Lock()
        self._warned = False

    def __repr__(self) -> str:
        return f"ClientCredentialsToken(client_id={self.client_id!r}, token_url={self.token_url!r})"

    def get(self) -> str | None:
        if not self._secret:
            if not self._warned:
                log.warning("no client secret configured for %s: calls to the api are unauthenticated",
                            self.client_id)
                self._warned = True
            return None
        with self._lock:
            if self._token is None or self.clock() >= self._expires_at - self.refresh_margin:
                self._fetch()
            return self._token

    def invalidate(self) -> None:
        with self._lock:
            self._token = None
            self._expires_at = 0.0

    def _fetch(self) -> None:
        try:
            resp = self.http.post(self.token_url, data={"grant_type": "client_credentials",
                                                        "client_id": self.client_id,
                                                        "client_secret": self._secret})
        except httpx.HTTPError as exc:
            log.warning("token endpoint unavailable for %s: %s", self.client_id, exc.__class__.__name__)
            self._token = None
            return
        if resp.status_code != 200:
            log.warning("token request for %s refused: HTTP %d", self.client_id, resp.status_code)
            self._token = None
            return
        try:
            body = resp.json()
            token = str(body["access_token"])
            expires_in = float(body.get("expires_in", 60))
        except (ValueError, KeyError, TypeError):
            log.warning("malformed token response for %s", self.client_id)
            self._token = None
            return
        self._token = token
        self._expires_at = self.clock() + expires_in
        log.info("service token for %s refreshed (expires in %.0f s)", self.client_id, expires_in)


class StaticToken:
    """The asking user's bearer token: never refreshed (a 401 means the user must re-authenticate)."""

    refreshable = False

    def __init__(self, token: str):
        self._token = token

    def __repr__(self) -> str:
        return "StaticToken(***)"

    def get(self) -> str | None:
        return self._token

    def invalidate(self) -> None:  # the user's token cannot be refreshed here
        return None
