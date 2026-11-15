# Fișa de demo — FileGrid PPC

Scenariu de ~8 minute pentru juriu. Fiecare pas: **ce faci**, **ce se vede**, **ce spui**. Totul rulează local
(Docker Compose); datele de contor sunt simulate și etichetate „DATE SIMULATE”.

## Înainte de demo (10 min)

- [ ] `docker compose ps` → toate serviciile `healthy` (în afară de `tunnel`, care n-are healthcheck).
- [ ] https://localhost → login cu `admin.demo` (parola: `KC_DEMO_ADMIN_PASSWORD` din fișierul de mediu). Ctrl+F5 o dată.
- [ ] În Control Center, sub case apar numerele de fișiere și „… citiri/min” pe linia contoarelor.
- [ ] Niciun storage în mentenanță (fără barieră portocalie pe servere).
- [ ] Pregătește pe desktop: un **PDF** (document de racordare), un **.csv** (înregistrare de avarie), opțional un **.mp4** mic.
- [ ] WhatsApp (opțional): tunelul pornit, URL-ul curent în fișierul de mediu **și** în Twilio, `docker compose up -d api`; trimite
      „salut” de pe telefon înainte (regula de 24 h). Pe contul trial, răspunsurile lui PPCastor nu ajung pe telefon (eroarea 21654).
- [ ] Grafana deschisă într-un tab: https://localhost/grafana/d/filegrid-latency.
- [ ] Închide incidentele de test din `/incidents`, ca lista să fie curată.

## 1. Problema și ideea (30 s) — ecranul de login

**Spui:** „Un operator de distribuție are patru tipuri de fișiere — citiri de contor, înregistrări de avarie, documente
de racordare cu date personale, video-uri de dronă — și trei feluri de stocare. FileGrid decide automat unde merge
fiecare, explică de ce și verifică că a ajuns intact.” Te autentifici: Keycloak, roluri, PKCE.

## 2. Control Center — orașul (1 min)

**Se vede:** PPCastor (Agentul 01) la baraj, cele 4 case (categorii), cele 3 servere (Database, S3, FTP), linia punctată
„… citiri/min” de la Citiri contoare spre Database — simulatorul trimite continuu.

**Spui:** „Animația nu e decor: fiecare mișcare vine dintr-un eveniment real din backend (SSE). Contoarele sunt deja
peste un milion de fișiere, scrise fără dubluri.”

## 3. Upload + rutare explicată (1,5 min)

**Faci:** tragi **PDF-ul** pe PPCastor.
**Se vede:** PPCastor cară fișierul ca pe un buștean la casa *Documente de racordare*, linia roz se conectează la **S3**,
mesajul „Decizia lui PPCastor”, apoi „Stocat și verificat”.
**Faci:** „Criterii și alternative” → Decision Explorer.
**Spui:** „Agentul 01 e determinist, nu un LLM: scor pe criterii, alternative respinse cu motiv. Documentul conține date
personale, deci e criptat aplicativ înainte să ajungă în S3 — în S3 există doar ciphertext.”
**Faci (opțional):** tragi **.csv** → merge la *Înregistrări de avarie* → **FTP**.

## 4. Mentenanță planificată → fallback live (1,5 min) — momentul-cheie

**Faci:** pe serverul **S3**, butonul cu cheie franceză (doar adminul îl vede) → S3 primește bariera portocalie „În mentenanță”.
**Faci:** tragi din nou un **PDF**.
**Se vede:** PPCastor îl duce la **Database** (fallback-ul politicii), criptat.
**Faci:** Decision Explorer → la S3: „destinația este în mentenanță planificată (pornită de administrator din Control Center)”.
**Spui:** „Nicio destinație nu e hard-codată. Dacă un storage iese din rutare — mentenanță sau cădere — fișierele merg pe
alternativa din politică, iar motivul e scris în decizie.”
**Faci:** butonul din nou → S3 revine în rutare.

## 5. Integritate și File Passport (45 s)

**Faci:** din Village, click pe casa documentelor → ultimul fișier → pașaportul.
**Se vede:** SHA-256, timeline-ul stărilor, decizia, „Verifică integritatea”.
**Spui:** „«Stocat și verificat» apare doar după ce am citit fișierul înapoi din storage și hash-ul a coincis.”

## 6. Rețeaua și incidentele (1 min)

**Faci:** `/grid` (Grid Intelligence) → constatările Agentului 02 pe datele simulatorului.
**Spui:** „Agentul 02 nu afirmă cauze: arată observații, dovezi, încredere și ce trebuie verificat de om.”
**Faci (opțional, WhatsApp):** de pe telefon `raport fum la PT-DOB-04-03`, apoi `DA` → în `/incidents` apare incidentul
**REAL**, deschis din teren.

## 7. Asistentul PPCastor (45 s)

**Faci:** butonul „PPCastor · Asistent” → întrebi „Câte avarii au fost azi în zona DOB?”.
**Spui:** „Răspunde din datele aplicației, cu sursa marcată: Gemini sau mod de rezervă, date reale sau simulate.”

## 8. Observabilitate (1 min) — Grafana

**Faci:** dashboard-ul *Latență* → p50/p95/p99 pe categorie × storage; *Cozi și workeri* → bulkhead.
**Spui:** „Fiecare cerință are metrică și alertă de business: storage căzut, avarii întârziate, date personale în pericol
→ incident automat.” Opțional: Explore → Tempo → un trace de la simulator până în worker.

## Dacă ceva nu merge

| Simptom | Ce faci |
| --- | --- |
| Casele arată „—” | așteaptă 10 s (sumarul se calculează în fundal); dacă persistă: `docker compose restart api` |
| Upload-ul rămâne „în așteptare” | `docker compose ps` — un storage căzut? Fișierul se reia singur când revine |
| S3 rămâne cu bariera | butonul cu cheie franceză din nou, sau `DELETE /api/admin/storage/S3/maintenance` |
| Asistentul răspunde „mod de rezervă” | cheia Gemini lipsește sau limita pe minut (429): e normal, răspunsul vine din date |
| Pagina de login arată ciudat | Ctrl+F5 (resursele temei sunt în cache 30 de zile) |
