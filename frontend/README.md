# FileGrid — frontend (Angular 21)

Standalone components, signals, zoneless, strict TS, Angular Material (dark theme), ECharts (lazy), GSAP, tus-js-client.

- `src/app/core/texts.ts` — all Romanian UI texts (single i18n-ready catalogue).
- `src/app/core/` — API client (`api.service.ts`), SSE streams with reconnect/backoff (`events.service.ts`),
  resumable tus uploads + per-file live timeline (`upload.service.ts`), Web Speech wrapper (`voice.ts`).
- `src/app/pages/` — Control Center (`/`, FileGrid Village), upload, File Explorer, categories, File Passport,
  Decision Explorer, policies, incidents (+ detail), Grid Intelligence, AI Assistant, field communication, performance.
- `src/app/features/` — reusable pieces: Village decision panel, grid map, assistant chat (global side panel), simulated
  WhatsApp phone, incident report dialog, field voice calls.

Commands: `npm ci && npm run build` (output `dist/filegrid-frontend/browser`), `npm start` for a dev server
(calls go to relative `/api`, so put it behind the gateway or add a proxy). Docker: `docker compose up -d --build frontend`
(nginx-unprivileged on :8080, SPA fallback, `/healthz`); Traefik routes `PathPrefix(/)` with priority 1.
