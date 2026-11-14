# ADR-0005 — Agent 01 (rutarea) este determinist, nu un LLM

- **Stare:** acceptat (arhitectură, `docs/ARCHITECTURE.md` §A.3.1 și §A.3.2)
- **Etapa:** 3

## Context

Agent 01 decide în ce storage ajunge fiecare fișier. Decizia afectează integritatea datelor de facturare, protecția datelor personale și disponibilitatea pentru dispecerat. Cerința spune că politica per categorie trebuie să fie „configurabilă, fără modificarea codului”, iar UI-ul trebuie să poată arăta de ce s-a luat o decizie. Un LLM ar fi nedeterminist, lent, dependent de un serviciu extern și greu de auditat: aceeași intrare ar putea da decizii diferite, iar explicația ar putea să nu corespundă motivului real.

## Decizie

1. **Motor de politici cu scorare, fără I/O** (`backend/src/main/java/ro/filegrid/routing/RoutingEngine.java`): verifică întâi constrângerile hard (`maxSizeBytes`, `needsResumable`, `needsImmediateRead`, `idempotent`, `needsEncryption`), apoi disponibilitatea din `storage_health` (`HealthTableStorageStateProvider`), apoi calculează un scor ponderat (`weights` din politică). Aceleași intrări dau mereu aceeași decizie.
2. **Clasificare deterministă** (`classification/ClassificationService.java`): categoria declarată + MIME detectat cu Apache Tika din primii 64 KB + extensie + câmpuri obligatorii; avertismentele nu blochează încărcarea.
3. **Decizia se salvează înainte de transfer** (`routing/RoutingService.java`, tabelul `routing_decision`): criterii, scoruri, alternative și motivele respingerii. Explicația în română se construiește din motive structurate prin template-uri (`i18n/texts_ro.properties`), nu din text liber. Decision Explorer (`GET /api/files/{id}/decision`, pagina `/decisions`) arată motivele reale.
4. **Politica este date, nu cod**: `config/policies.yaml` montat în container, validat strict (`PolicyParser`, `PolicyValidator`), versionat append-only în `policy_version`, reîncărcat fără restart (`PolicyFileWatcher`), editabil și din `PUT /api/admin/policies`. O politică invalidă este respinsă, iar versiunea anterioară rămâne activă.
5. **LLM-urile se folosesc doar în serviciul `intelligence`** (AI Operations Assistant), cu tools read-only; asistentul nu poate schimba rutarea.

## Alternative evaluate

| Alternativă | De ce nu |
| --- | --- |
| LLM care alege storage-ul | nedeterminist, cost și latență per fișier, dependență de rețea externă, explicații neverificabile |
| Reguli hardcodate în Java | încalcă cerința „configurabil fără modificarea codului” |
| Model ML antrenat pe benchmark | nu există încă date suficiente; scorarea ponderată cu latența măsurată acoperă nevoia și rămâne explicabilă |

## Consecințe

- Deciziile sunt reproductibile și testabile unitar; auditul arată exact versiunea de politică folosită.
- Calitatea deciziei depinde de calitatea politicii și a măsurătorilor: latența folosită la scorare vine deocamdată din probe-ul de sănătate (`HEALTH_PROBE`), nu din p95 măsurat pe transferuri reale (Etapa 8, în lucru).
- Politica inițială este o ipoteză (ADR-0006).

## Verificare

- `RoutingEngineTest`, `PolicyValidationTest`, `ClassificationServiceTest` (unit), `PolicyAndRoutingIT` (Testcontainers).
- Live: modificarea `maxSizeBytes` în `config/policies.yaml` creează o versiune nouă în ~10 s (`GET /api/policies/history`), fără restart.
