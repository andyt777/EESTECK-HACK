# FileGrid PPC — reguli pentru agent
## Surse de adevăr
docs/ARCHITECTURE.md (arhitectură, NU se schimbă fără aprobarea arhitectului), docs/cerinta-FileGrid_PPC.pdf (cerințe evaluate), docs/experienta-utilizator.md (UX, texte, fluxuri).
Dacă o cerință ți se pare imposibilă sau o tehnologie nepotrivită: OPREȘTE-TE și explică, nu improviza.
## Stack
Backend: Java 21, Spring Boot 3.x, Maven wrapper, Flyway, Testcontainers. Pachete organizate pe feature.
Frontend: Angular (ultima versiune stabilă), standalone components, signals, TypeScript strict, Angular Material, ECharts, GSAP.
Python: 3.12, uv, ruff, pytest, FastAPI.
Infra: Docker Compose (calea principală), Kubernetes (kind) ulterior.
## Reguli de cod
- Identificatori, cod și commit-uri în engleză; texte UI în română (centralizate, pregătite pentru i18n).
- Streaming obligatoriu: niciodată un fișier întreg în memorie (fără byte[] pe tot conținutul); buffere maxim 16MB.
- SHA-256 calculat în flux; succesul se raportează doar după verificare.
- Fiecare stare afișată în UI corespunde unei stări reale din backend.
- Datele generate/simulate au dataOrigin=SIMULATED.
- Fără secrete în repo: totul din variabile de mediu; actualizează .env.example la orice variabilă nouă.
- Fiecare funcționalitate are teste (unit + integrare cu Testcontainers acolo unde atinge infrastructura).
- Autentificarea vine într-o etapă ulterioară: până atunci folosește interfețele CurrentActorProvider (returnează "demo-user") și AccessPolicy (permisivă), apelate peste tot unde va fi nevoie de autorizare. Nu scrie cod care face greu de adăugat securitatea.
- Agent 01 (rutarea) este determinist. LLM-urile se folosesc doar în serviciul intelligence pentru asistent.
## Finalul fiecărei etape
1. Rulează toate testele și `docker compose up` (când există) și confirmă că totul e healthy.
2. Actualizează README.md și docs/CHANGELOG-ETAPE.md (ce s-a făcut, cum se verifică, limitări cunoscute).
3. Raportează: fișiere create/modificate, comenzi de verificare, rezultatele testelor, ce a rămas neterminat.
4. Propune un mesaj de commit. Nu face commit/push decât dacă ți se cere.

@docs/ARCHITECTURE.md
