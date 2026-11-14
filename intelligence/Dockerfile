# FileGrid PPC - intelligence service (Agent 02 + AI Operations Assistant), Python 3.12 + uv.
#   docker build -t filegrid/intelligence:local intelligence              # runtime image
#   docker build --target test -t filegrid/intelligence:test intelligence # runs ruff + pytest during the build
FROM ghcr.io/astral-sh/uv:0.7.3-python3.12-bookworm-slim AS base
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never \
    PYTHONUNBUFFERED=1
WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project
COPY src ./src
RUN uv sync --frozen --no-dev

FROM base AS test
RUN uv sync --frozen
COPY tests ./tests
RUN uv run ruff check src tests && uv run pytest -q

FROM python:3.12-slim-bookworm AS runtime
RUN useradd --uid 10003 --create-home intelligence
WORKDIR /app
COPY --from=base /app/.venv /app/.venv
COPY --from=base /app/src /app/src
ENV PATH="/app/.venv/bin:$PATH" PYTHONUNBUFFERED=1
USER intelligence
EXPOSE 8000
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=4)"
ENTRYPOINT ["filegrid-intelligence"]
