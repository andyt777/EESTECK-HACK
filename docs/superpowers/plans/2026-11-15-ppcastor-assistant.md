# PPCastor Assistant Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the robot (`smart_toy`) of the AI assistant with a second PPCastor (headset + tablet), whose expression follows the real request state, and give the LLM the PPCastor persona.

**Architecture:** One standalone SVG component `fg-ppcastor-avatar` (inputs `mood`, `size`, `full`), a pure function `moodFor()` that maps a chat message/busy state to a mood, used by `assistant-chat.ts`, `app.html`, `app.ts` nav and the "Întreabă asistentul" buttons. Persona line added to `SYSTEM_PROMPT` in intelligence.

**Tech Stack:** Angular (standalone, signals), Vitest (`ng test`), Python 3.12 + pytest (run via `docker build --target test intelligence`).

**Spec:** `docs/superpowers/specs/2026-11-15-ppcastor-assistant-design.md`

## Global Constraints
- Village PPCastor (`pages/control/village.*`) is not modified.
- Provider/dataOrigin badges stay exactly as they are.
- UI texts in Romanian, centralised in `core/texts.ts`; identifiers in English.
- The avatar is decorative: `aria-hidden="true"`; the name/role is always present as text.
- Colours only from tokens in `frontend/src/styles.scss` (`--pink`, etc.); fur/cream/hat may reuse the Village fills.
- Files use CRLF: use exact edits, not `\n` regex replacements.

## Review Focus
- Unavailable (`m.unavailable`) and fallback (`provider === 'SIMULATED'`) answers must not show the happy face → `moodFor` test covers both.
- Avatar inside a `.btn sm` / nav item must not break line height or alignment → visual check in Task 3.
- Dark/forced-colors: SVG strokes must stay visible → use `currentColor` for outlines.
- The persona must not let the LLM claim it routes files → prompt sentence + pytest assertion in Task 4.
- Compact drawer (narrow): avatar per bubble must not squeeze the answer → size 28 in compact, 36 otherwise.

---

### Task 1: `fg-ppcastor-avatar` + `moodFor`

**Files:**
- Create: `frontend/src/app/shared/ppcastor.ts` (component + `moodFor`)
- Test: `frontend/src/app/shared/ppcastor.spec.ts`

**Interfaces:**
- Produces: `type PpcastorMood = 'idle' | 'thinking' | 'happy' | 'error'`;
  `function moodFor(m: { role: 'user' | 'assistant'; error?: string; unavailable?: boolean; res?: { provider?: string } } | null, busy: boolean): PpcastorMood`;
  component `PpcastorAvatar`, selector `fg-ppcastor-avatar`, inputs `mood = input<PpcastorMood>('idle')`, `size = input<number>(32)`, `full = input<boolean>(false)` (full body with tablet; otherwise head + headset).

- [ ] **Step 1: Write the failing test** — `moodFor(null, true) === 'thinking'`; `moodFor(null, false) === 'idle'`; `{role:'assistant', error:'x'}` → `'error'`; `{unavailable:true}` → `'error'`; `{res:{provider:'SIMULATED'}}` → `'error'`; `{res:{provider:'GEMINI'}}` → `'happy'`.
- [ ] **Step 2: Run** `cd frontend && npx ng test --watch=false` — Expected: FAIL (module not found).
- [ ] **Step 3: Implement** `moodFor` and `PpcastorAvatar` (inline SVG, `viewBox` sized for head or full body, `[attr.width]="size()"`; eyes switch on mood like Village: idle = dots, thinking = flat lines looking up, happy = arcs, error = worried circles; headset band over the hard hat with a mic boom; full body holds a tablet in front of the vest).
- [ ] **Step 4: Run** tests — Expected: PASS.
- [ ] **Step 5: Commit** `feat(frontend): PPCastor assistant avatar component`

### Task 2: Chat uses PPCastor

**Files:**
- Modify: `frontend/src/app/features/assistant-chat.ts` (empty state line 28, assistant bubbles, busy bubble)
- Modify: `frontend/src/app/core/texts.ts` (`assistant` block, ~line 401)

**Interfaces:** Consumes `PpcastorAvatar`, `moodFor` from Task 1.

- [ ] **Step 1:** texts: `title: 'PPCastor · Asistent'`, `name: 'PPCastor'`, `role: 'Asistent'`, `hello: 'Salut! Sunt PPCastor, asistentul tău. Te ajut cu fișiere, incidente, constatări și starea platformei — și îți arăt mereu sursele.'`, `thinking: 'PPCastor caută în datele FileGrid…'`, `notRouter: 'Fișierele le rutează Agent 01 (reguli fixe). Eu doar răspund la întrebări.'` (hint under hello, muted).
- [ ] **Step 2:** empty state: `<fg-ppcastor-avatar [full]="true" [size]="compact() ? 96 : 140" />` + `hello` + `notRouter` + examples. Each assistant-side bubble (answer, error, unavailable) is preceded by `<fg-ppcastor-avatar [mood]="moodFor(m, false)" [size]="compact() ? 28 : 36" />`; busy bubble uses `mood="thinking"`. Layout: `.msg:not(.me)` becomes `display:flex; gap:8px; align-items:flex-start`.
- [ ] **Step 3: Run** `cd frontend && npx ng test --watch=false && npx ng build` — Expected: PASS, build OK.
- [ ] **Step 4: Commit** `feat(frontend): assistant chat speaks as PPCastor`

### Task 3: Shell and entry points

**Files:**
- Modify: `frontend/src/app/app.html:55,67,18`, `frontend/src/app/app.ts:41` (nav item gets `avatar: true` flag; template renders avatar instead of `.ms` icon when set)
- Modify: `pages/grid/grid-page.html:111`, `pages/incidents/incident-detail.ts:33`, `pages/passport/passport-page.html:30` (+ their `imports`)
- Modify: `pages/assistant/assistant-page.ts` (kicker stays "Agentic RAG"; title from texts)

- [ ] **Step 1:** replace every `smart_toy` span with `<fg-ppcastor-avatar [size]="20" />` (header/nav/buttons) and `[size]="24"` in the drawer head.
- [ ] **Step 2: Run** `grep -rn smart_toy frontend/src` — Expected: no matches. `npx ng build` — OK.
- [ ] **Step 3: Visual check** in the browser (https://localhost as `admin.demo`): header button, nav, drawer, `/assistant`, a real answer, a fallback answer.
- [ ] **Step 4: Commit** `feat(frontend): PPCastor replaces the robot icon everywhere for the assistant`

### Task 4: LLM persona

**Files:**
- Modify: `intelligence/src/filegrid_intelligence/assistant.py:37`
- Test: `intelligence/tests/test_assistant.py`

- [ ] **Step 1: Write the failing test** `test_system_prompt_ppcastor_persona`: `"Ești PPCastor" in SYSTEM_PROMPT` and `"Agent 01" in SYSTEM_PROMPT` and `"nu rutezi" in SYSTEM_PROMPT`.
- [ ] **Step 2: Run** `docker build --target test intelligence` — Expected: FAIL.
- [ ] **Step 3: Implement** first sentence: `Ești PPCastor, castorul-electrician care este asistentul AI al FileGrid PPC …`; add rule: `- Tu nu rutezi fișiere: rutarea o face Agent 01, un motor de reguli determinist. Poți explica deciziile lui doar din datele returnate de instrumente.`; tone line: `Ton prietenos și scurt, fără glume despre date sau incidente.` Other rules unchanged.
- [ ] **Step 4: Run** tests — Expected: PASS.
- [ ] **Step 5: Commit** `feat(intelligence): PPCastor persona for the assistant`

### Task 5: Docs and final verification

- [ ] Rebuild: `docker compose … up -d --build --no-deps frontend intelligence`; confirm healthy.
- [ ] Update `README.md`, `docs/CHANGELOG-ETAPE.md`, `PRODUCT.md` (mascot line: two PPCastors, same look, different jobs), `docs/HANDOFF.md` ("panoul asistentului" item).
- [ ] Commit `docs: PPCastor assistant`.
