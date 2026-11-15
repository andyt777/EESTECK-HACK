# PPCastor as the AI assistant — design (short)

## Intent
The AI Operations Assistant gets the PPCastor mascot as its face, so the whole product has one recognisable character.
It is a **second, separate PPCastor**: same mascot, different job. Village PPCastor stays Agent 01 (deterministic routing);
the assistant PPCastor answers questions through Gemini (agentic RAG). They must never be confused.

## Goals
- Every assistant surface shows PPCastor instead of the robot (`smart_toy`): header button, nav item, side panel,
  `/assistant` page, empty state, "thinking", answer, error/fallback.
- Visual difference from Agent 01: headset with microphone + tablet (no log); role label "Asistent" vs "Agent 01".
- Each expression maps to a real request state (idle, waiting for `/chat`, answered, error/unavailable/fallback).
- LLM persona: system prompt starts "Ești PPCastor, asistentul…", friendly tone, same rules on tools, citations, dataOrigin.

## Non-goals / anti-goals
- No change to the Village PPCastor, its animations or its state machine.
- No change to the provider/dataOrigin badges: they stay explicit ("Răspuns: Gemini / mod de rezervă", "Date: simulate / reale").
- The assistant must not claim it routes files or that Agent 01 uses an LLM.

## Approaches
1. **Chosen:** new reusable standalone component `fg-ppcastor-avatar` (inline SVG, inputs `mood` and `size`).
2. Inline SVG copied into each place — rejected: duplicated drawing in 3–4 places.
3. Generated PNG — rejected: no per-state expressions, clashes with the Village SVG style.

## Design
- `frontend/src/app/shared/ppcastor-avatar.ts`: SVG head-and-shoulders beaver (fur, cream muzzle, teeth, white hard hat,
  pink hi-vis vest) + headset; `mood: 'idle' | 'thinking' | 'happy' | 'error'` changes eyes; full-body variant with tablet
  for the empty state (`size` large). Colours from existing tokens in `styles.scss`. Decorative (`aria-hidden`), the name is text.
- `assistant-chat.ts`: empty state = large PPCastor + greeting; each assistant bubble gets a small avatar; "thinking" bubble
  uses `mood='thinking'`; error/unavailable/fallback use `mood='error'`; normal answer `mood='happy'`.
- `app.html` / `app.ts`: header button and side-panel title use the small avatar; nav item keeps an icon slot (avatar).
- `texts.ts`: title "PPCastor · Asistent", greeting and thinking texts in PPCastor's voice (Romanian).
- `intelligence/.../assistant.py`: persona line in `SYSTEM_PROMPT`; fallback (no-LLM) answers keep their wording.

## Left to the builder
Exact SVG paths, sizes, and spacing; whether the nav icon uses the avatar or stays a Material icon if it hurts alignment.

## Verification
Frontend build + existing tests; intelligence tests (`docker build --target test intelligence`); check in the browser
(panel, page, thinking, fallback) and update README/CHANGELOG-ETAPE.
