# intelligence

FastAPI — Agent 02 (Grid Intelligence) și AI Operations Assistant (Etapele 15–16).

Autentificare (Etapa 11): fiecare rută `/api/intelligence/**` (în afară de `/health`) cere `Authorization: Bearer` și o permisiune verificată prin `GET /api/me` (vezi `docs/AUTH-DESIGN.md`). Asistentul și vocea apelează api-ul cu tokenul utilizatorului; Agent 02 în fundal folosește contul de serviciu `filegrid-intelligence` (`KC_INTELLIGENCE_CLIENT_ID`, `KC_INTELLIGENCE_SECRET`, `KC_TOKEN_URL`).
