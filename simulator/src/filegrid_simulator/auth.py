"""OAuth2 client_credentials token for the ``filegrid-simulator`` Keycloak client (docs/AUTH-DESIGN.md §4).

The token is cached and refreshed ``refresh_margin`` seconds before it expires; ``invalidate()`` forces a new
one (used after a 401). If the token endpoint cannot be reached, ``TokenUnavailable`` is raised and the
caller retries the batch with backoff like any transient failure: a reading is never dropped because
Keycloak is briefly down. The token and the secret are never logged.
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Callable

import httpx

log = logging.getLogger(__name__)


class TokenUnavailable(Exception):
    """No token could be obtained right now (network error, 5xx, refused credentials, bad response)."""


class ClientCredentialsToken:
    def __init__(
        self,
        http: httpx.AsyncClient,
        token_url: str,
        client_id: str,
        client_secret: str,
        refresh_margin: float = 30.0,
        clock: Callable[[], float] = time.monotonic,
    ):
        self.http = http
        self.token_url = token_url
        self.client_id = client_id
        self._secret = client_secret
        self.refresh_margin = refresh_margin
        self.clock = clock
        self._token: str | None = None
        self._expires_at = 0.0
        self._lock = asyncio.Lock()

    def __repr__(self) -> str:
        return f"ClientCredentialsToken(client_id={self.client_id!r}, token_url={self.token_url!r})"

    async def get(self) -> str:
        async with self._lock:  # concurrent batches share one refresh
            if self._token is None or self.clock() >= self._expires_at - self.refresh_margin:
                return await self._fetch()
            return self._token

    def invalidate(self) -> None:
        self._token = None
        self._expires_at = 0.0

    async def _fetch(self) -> str:
        form = {
            "grant_type": "client_credentials",
            "client_id": self.client_id,
            "client_secret": self._secret,
        }
        try:
            response = await self.http.post(self.token_url, data=form)
        except httpx.HTTPError as error:
            raise TokenUnavailable(f"token endpoint unreachable: {error.__class__.__name__}") from None
        if response.status_code != 200:
            raise TokenUnavailable(f"token request refused: HTTP {response.status_code}")
        try:
            body = response.json()
            token = str(body["access_token"])
            expires_in = float(body.get("expires_in", 60))
        except (ValueError, KeyError, TypeError):
            raise TokenUnavailable("malformed token response") from None
        self._token = token
        self._expires_at = self.clock() + expires_in
        log.info("Service token for %s refreshed (expires in %.0f s)", self.client_id, expires_in)
        return token
