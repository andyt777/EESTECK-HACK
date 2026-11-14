"""Stage 11: client_credentials token cache, refresh before expiry, one retry on 401."""

import asyncio
import json

import httpx

from filegrid_simulator.auth import ClientCredentialsToken
from filegrid_simulator.client import FileGridClient
from filegrid_simulator.config import load

TOKEN_URL = "http://keycloak.test/auth/realms/filegrid/protocol/openid-connect/token"
API = "http://filegrid.test/api"


async def no_sleep(_seconds):
    return None


class Fake:
    """Keycloak token endpoint + FileGrid api in one MockTransport handler."""

    def __init__(self, expires_in: int = 300, reject_tokens: tuple[str, ...] = (), token_down: int = 0):
        self.issued = 0
        self.expires_in = expires_in
        self.reject_tokens = set(reject_tokens)
        self.token_down = token_down  # number of token requests that fail with 503
        self.forms: list[str] = []
        self.api_auth: list[str | None] = []
        self.keys: list[str] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/token"):
            self.forms.append(request.content.decode())
            if self.token_down > 0:
                self.token_down -= 1
                return httpx.Response(503)
            self.issued += 1
            return httpx.Response(
                200, json={"access_token": f"tok-{self.issued}", "expires_in": self.expires_in}
            )
        auth = request.headers.get("authorization")
        self.api_auth.append(auth)
        self.keys.append(request.headers["Idempotency-Key"])
        if auth is None or auth.removeprefix("Bearer ") in self.reject_tokens:
            return httpx.Response(401)
        n = len(json.loads(request.content)["readings"])
        return httpx.Response(202, json={"accepted": [str(i) for i in range(n)], "duplicate": []})


async def test_token_is_cached_and_refreshed_30s_before_expiry():
    now = [0.0]
    fake = Fake(expires_in=300)
    async with httpx.AsyncClient(transport=httpx.MockTransport(fake)) as http:
        tokens = ClientCredentialsToken(http, TOKEN_URL, "filegrid-simulator", "s3cret", clock=lambda: now[0])
        client = FileGridClient(http, API, sleep=no_sleep, tokens=tokens)
        await client.send([{"meterId": "M1"}])
        now[0] = 269
        await client.send([{"meterId": "M2"}])
        assert fake.issued == 1
        now[0] = 270
        await client.send([{"meterId": "M3"}])
    assert fake.issued == 2
    assert fake.api_auth == ["Bearer tok-1", "Bearer tok-1", "Bearer tok-2"]
    assert "grant_type=client_credentials" in fake.forms[0]
    assert "client_id=filegrid-simulator" in fake.forms[0]
    assert "s3cret" not in repr(tokens)


async def test_401_gets_a_new_token_and_one_retry_with_the_same_key():
    fake = Fake(reject_tokens=("tok-1",))
    async with httpx.AsyncClient(transport=httpx.MockTransport(fake)) as http:
        tokens = ClientCredentialsToken(http, TOKEN_URL, "filegrid-simulator", "s")
        result = await FileGridClient(http, API, sleep=no_sleep, tokens=tokens).send([{"meterId": "M1"}])
    assert not result.rejected and result.accepted == 1 and result.attempts == 2
    assert fake.api_auth == ["Bearer tok-1", "Bearer tok-2"]
    assert len(set(fake.keys)) == 1


async def test_persistent_401_is_retried_only_once():
    fake = Fake(reject_tokens=("tok-1", "tok-2", "tok-3"))
    async with httpx.AsyncClient(transport=httpx.MockTransport(fake)) as http:
        tokens = ClientCredentialsToken(http, TOKEN_URL, "filegrid-simulator", "s")
        result = await FileGridClient(http, API, sleep=no_sleep, tokens=tokens).send([{"meterId": "M1"}])
    assert result.rejected and result.attempts == 2
    assert fake.issued == 2


async def test_token_endpoint_down_is_retried_like_a_transient_error():
    fake = Fake(token_down=2)
    async with httpx.AsyncClient(transport=httpx.MockTransport(fake)) as http:
        tokens = ClientCredentialsToken(http, TOKEN_URL, "filegrid-simulator", "s")
        result = await FileGridClient(http, API, sleep=no_sleep, tokens=tokens).send([{"meterId": "M1"}])
    assert not result.rejected and result.attempts == 3
    assert fake.api_auth == ["Bearer tok-1"]


async def test_concurrent_batches_share_one_token_request():
    fake = Fake()
    async with httpx.AsyncClient(transport=httpx.MockTransport(fake)) as http:
        tokens = ClientCredentialsToken(http, TOKEN_URL, "filegrid-simulator", "s")
        client = FileGridClient(http, API, sleep=no_sleep, tokens=tokens)
        await asyncio.gather(*(client.send([{"meterId": f"M{i}"}]) for i in range(5)))
    assert fake.issued == 1


async def test_without_tokens_no_authorization_header_is_sent():
    seen = []

    def handler(request):
        seen.append(request.headers.get("authorization"))
        return httpx.Response(202, json={"accepted": ["a"], "duplicate": []})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        await FileGridClient(http, API, sleep=no_sleep).send([{"meterId": "M1"}])
    assert seen == [None]


def test_keycloak_settings_come_from_the_environment():
    settings = load([], env={"KC_SIMULATOR_SECRET": "abc", "KC_TOKEN_URL": TOKEN_URL})
    assert settings.client_secret == "abc" and settings.token_url == TOKEN_URL
    assert settings.client_id == "filegrid-simulator"
    assert "abc" not in repr(settings)
    defaults = load([], env={})
    assert defaults.client_secret == ""
    assert defaults.token_url == "http://keycloak:8080/auth/realms/filegrid/protocol/openid-connect/token"
