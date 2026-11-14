# ADR-0007 — Autentificare cu Keycloak (OIDC) și autorizare pe roluri

- **Stare:** acceptat (decizie a arhitectului, 2026-11-14); implementare **în lucru** (Etapa 11)
- **Etapa:** 11
- **Design detaliat:** `docs/AUTH-DESIGN.md` (contractul comun backend / frontend / intelligence / simulator)

## Context

Cerința de securitate: „traficul din exterior este criptat, iar accesul la FileGrid este autentificat”. Până la Etapa 11, codul folosește `CurrentActorProvider` (returnează `demo-user`) și `AccessPolicy` (permisivă), apelate în toate punctele unde trebuie autorizare (`backend/src/main/java/ro/filegrid/security/`). Utilizatorii au roluri diferite (teren, dispecerat, racordări etc.), iar datele personale din documentele de racordare trebuie să fie vizibile doar celor care le procesează. Pe lângă oameni, există clienți tehnici: simulatorul de contoare, Alertmanager (webhook) și serviciul `intelligence`.

## Decizie

1. **Keycloak** ca furnizor de identitate OIDC, în rețeaua `internal`, publicat prin Traefik doar sub `/auth/realms` și `/auth/resources` (consola de admin nu este rutată implicit). Realm-ul `filegrid` este definit ca fișier (realm as code), cu secretele și parolele demo doar ca placeholder-e `${ENV}` din `.env`.
2. **Clienți**:
   - `filegrid-web` — public, authorization code + PKCE S256 (Angular);
   - `filegrid-simulator`, `filegrid-alertmanager`, `filegrid-intelligence` — confidențiali, `client_credentials`, fiecare cu un rol de serviciu minim (`svc-meter-ingest`, `svc-alert-ingest`, `svc-intelligence`).
3. **Șase roluri pentru oameni**:

   | Rol (RO) | Identificator realm |
   | --- | --- |
   | teren | `field` |
   | dispecer | `dispatcher` |
   | racordări | `connections` |
   | analist | `analyst` |
   | manager incident | `incident-manager` |
   | admin | `admin` |

   **Doar `connections` (racordări) și `admin` au permisiunea `pii:read`.** Matricea completă rol → permisiune și endpoint → permisiune este în `docs/AUTH-DESIGN.md` §3.
4. **Backend = resource server** (JWT validat: issuer, semnătură prin JWKS, audiență `filegrid-api`). `AccessPolicy` rămâne **singurul punct de decizie**: implementarea permisivă este înlocuită de una bazată pe roluri → permisiuni; codul care o apelează nu se schimbă.
5. Serviciul `intelligence` (rutat direct de Traefik) verifică permisiunile apelantului prin `GET /api/me`, fail-closed; tools-urile asistentului folosesc token-ul utilizatorului, deci asistentul vede exact ce vede utilizatorul.

## Alternative evaluate

| Alternativă | De ce nu |
| --- | --- |
| Autentificare proprie în Spring (utilizatori în meta-db) | reinventează login, parole, brute-force protection, refresh tokens; risc mare de securitate |
| Basic auth / forward-auth în Traefik | fără roluri granulare, fără identitate propagată către api și intelligence |
| IdP extern (Entra ID, Auth0) | cerința „pornește pe o mașină curată cu o singură comandă” exclude dependența de un tenant extern |

## Consecințe

- Încă un serviciu (~768 MB memorie) și o bază de date `keycloak` în meta-db.
- SSE și link-urile de descărcare (img/video/tab nou) nu pot trimite antetul `Authorization`: se rezolvă prin streaming cu `fetch` și prin URL-uri semnate HMAC, cu durată scurtă (`docs/AUTH-DESIGN.md` §4).
- Testele de backend folosesc JWT-uri simulate (`spring-security-test`), nu un Keycloak în Testcontainers (incompatibilitate cu Testcontainers 1.21.4).

## Verificare

- **În lucru (Etapa 11).** Criteriile de acceptare sunt în `docs/AUTH-DESIGN.md` §7: matrice endpoint × rol (200/403), `connections.demo` deschide un document cu date personale, `field.demo` este refuzat și vede numele mascat.
