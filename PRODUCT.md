# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users
- **Primary audience of the upload space (Village): the hackathon jury, during the live demo.** The screen must explain the flow at a glance: a file is dropped, classified by Agent 01, routed to a storage, verified. (Confirmed 2026-11-15.)
- Day-to-day users, by role (docs/experienta-utilizator.md §2, Keycloak realm `filegrid`): field crews (`field`), dispatchers (`dispatcher`), connection-request processors (`connections`, the only role besides `admin` that reads personal data), grid analysts (`analyst`), incident managers (`incident-manager`), administrators (`admin`).

## Product Purpose
FileGrid PPC centralises the operational files of an electricity distribution operator: smart-meter readings, fault records, connection documents (may contain personal data) and drone inspection videos. A user drops or searches a file in one interface; Agent 01 classifies it, routes it to the right storage (Database, S3 or FTP), verifies it with SHA-256 and keeps an auditable File Passport. Success: any file is found again in seconds, every routing decision is explained, and storage failures fall back without loss.

## Positioning
Routing is explained, not hidden: every decision shows its criteria and alternatives (Decision Explorer), integrity is verified on read, and personal data is encrypted by the application before any storage sees it.

## Operating Context
- Hackathon demo for PPC (electricity distribution), shown on a projector; everything runs locally with Docker Compose.
- Meter readings come from a simulator and are labelled `dataOrigin=SIMULATED`; storages are emulated locally.
- Agents: Agent 01 (classification and routing, deterministic), Agent 02 (grid anomalies), Agent 03 (AI operations assistant, Gemini).

## Capabilities and Constraints
- Upload only by dragging a file onto the mascot in the Village (resumable tus upload); no upload form.
- The four categories are shown as four buildings; they are a visual organisation, not where files are physically stored.
- All animation is driven by real server events (SSE); "stored and verified" is shown only after `STORED_VERIFIED`.
- Romanian UI copy; code and identifiers in English.

## Brand Commitments
- Mascot: **PPCastor**, a beaver dressed as an energy line-worker (hard hat, high-visibility vest), replacing the former robot. In the Village it carries each file like a log to the category building. (User, 2026-11-15.)
- Setting of the upload space: a town running on green energy, with trees. (User, 2026-11-15.)
- Palette direction for the whole app: baby blue instead of the former deep blue, much more white, and the existing bubblegum pink kept. (User, 2026-11-15.)
- Avoid generic AI-generated looks. (User, 2026-11-15.)

## Evidence on Hand
- Live data from the running stack: category counts, storage health and latency, meter readings per minute, incidents, Agent 02 findings.
- No real customer data, logos or photography; meter data and demo documents are synthetic and labelled as such.

## Product Principles
1. Show the mechanism: the Village dramatises the real pipeline, never a decorative animation.
2. Honesty over theatre: simulated data is always labelled; success is shown only when verified.
3. One place for everything: upload, find, explain and audit without knowing where a file physically lives.
4. Least privilege is visible: actions a role may not take are hidden, personal data is masked.

## Accessibility & Inclusion
WCAG 2.1 AA: keyboard focus visible everywhere, icon-only controls named, reduced-motion mode keeps state feedback.
