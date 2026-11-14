# E2E Test Infra: FileGrid PPC — Stage 4 Hackathon Showcase

## Test Philosophy
- **Opaque-Box & Requirement-Driven**: Tests interact exclusively with public API endpoints (`/api/uploads`, `POST /api/files`, `GET /api/events/stream`, `GET /api/files/{id}`), AMQP brokers (`filegrid.jobs.*`, `filegrid.events`, `filegrid.dlx`), and target storages (PostgreSQL `store-db`, MinIO/S3, pure-ftpd). Zero dependency on internal Java implementation details.
- **Methodology**: Systematic 4-tier testing adhering to Category-Partition, Boundary Value Analysis (BVA), Pairwise Combinatorial Testing, and Real-World Industrial Workload Testing.
- **Memory Streaming Rigor**: Enforce non-buffering streaming through `<test.jvm.args>-Xmx256m</test.jvm.args>` in Maven Failsafe and live Docker Compose memory budget monitoring via `scripts/mem-report.sh` (<85% container limit, 0 OOM kills).

---

## Feature Inventory & Coverage Mapping (All 28 Features)

| # | Feature | Requirement Source | Tier 1 | Tier 2 | Tier 3 | Tier 4 |
|---|---------|-------------------|:------:|:------:|:------:|:------:|
| 1 | Tus 1.0 Server (`me.desair.tus:tus-java-server:2.0.0`) | R1, §A.2, PLAN:233 | 5 | 5 | ✓ | ✓ |
| 2 | Staging Direct Streaming (`/data/staging`) | R1, §A.3, PLAN:233 | 5 | 5 | ✓ | ✓ |
| 3 | Tus Metadata Parsing (zone, station, equipment, eventType, etc.) | R1, §A.2, PLAN:233 | 5 | 5 | ✓ | ✓ |
| 4 | Max Upload Size Config (default 5GB) | R1, PLAN:233 | 5 | 5 | ✓ | ✓ |
| 5 | Abandoned Upload Cleanup (> 24h purge) | R1, PLAN:233 | 5 | 5 | ✓ | ✓ |
| 6 | Small File Multipart Endpoint (`POST /api/files`) | R1, §A.2, PLAN:234 | 5 | 5 | ✓ | ✓ |
| 7 | Shared Staging Volume Mount (`staging-data:/data/staging`) | R1, R5, PLAN:233 | 5 | 5 | ✓ | ✓ |
| 8 | Flyway Migration V3 (`s3_multipart_transfer` schema) | R2, R3, PLAN:238 | 5 | 5 | ✓ | ✓ |
| 9 | Ingestion Lifecycle (`RECEIVED` → `ANALYZING` → `CATEGORIZED` → `DESTINATION_SELECTED`) | R2, §A.2, PLAN:235 | 5 | 5 | ✓ | ✓ |
| 10 | Streaming Plaintext SHA-256 (non-buffering digest) | R2, §A.2, PLAN:235 | 5 | 5 | ✓ | ✓ |
| 11 | RabbitMQ Category Queues & DLQ (`filegrid.jobs.*`, publisher confirms) | R2, §A.1, PLAN:235 | 5 | 5 | ✓ | ✓ |
| 12 | Event Fanout Exchange (`filegrid.events`) | R2, R4, PLAN:240 | 5 | 5 | ✓ | ✓ |
| 13 | Worker Category Filtering (`WORKER_CATEGORIES`, prefetch, concurrency) | R3, §A.1, PLAN:236 | 5 | 5 | ✓ | ✓ |
| 14 | Worker Streaming Transfer & Progress (~5% or 16MB events) | R3, §A.3, PLAN:236 | 5 | 5 | ✓ | ✓ |
| 15 | Read-back Hash Verification (`STORED_VERIFIED` + staging delete) | R3, §A.3.4, PLAN:236 | 5 | 5 | ✓ | ✓ |
| 16 | Integrity Mismatch Handling (`MISMATCH` status, delete corrupt, retry) | R3, §A.3.4, PLAN:236 | 5 | 5 | ✓ | ✓ |
| 17 | Storage Error Backoff & Jitter (policy engine retries) | R3, §A.4, PLAN:237 | 5 | 5 | ✓ | ✓ |
| 18 | Storage Fallback Trigger (primary exhausted → `FALLBACK` event) | R3, §A.4, PLAN:237 | 5 | 5 | ✓ | ✓ |
| 19 | Storage Failure PENDING State (all storages down → `PENDING` resume) | R3, §A.3.7, PLAN:237 | 5 | 5 | ✓ | ✓ |
| 20 | S3 Multipart Crash Resumption (DB tracked parts, `TRANSFER_RESUMED`) | R3, §A.3, PLAN:238 | 5 | 5 | ✓ | ✓ |
| 21 | Job Idempotency Guard (duplicate delivery ACK without rewrite) | R3, §A.3.5, PLAN:239 | 5 | 5 | ✓ | ✓ |
| 22 | SSE Event Stream Endpoint (`GET /api/events/stream` with filters) | R4, §A.2, PLAN:240 | 5 | 5 | ✓ | ✓ |
| 23 | Agent 01 Romanian Messages (§8.1 & `texts_ro.properties`) | R4, §8.1, PLAN:240 | 5 | 5 | ✓ | ✓ |
| 24 | Horizontal Worker Scaling (`--scale worker-video=3`) | R5, PLAN:241 | 5 | 5 | ✓ | ✓ |
| 25 | 1.5GB Tus Interrupted Upload Test (interrupted at 40%, clean resume) | R5, PLAN:242 | 5 | 5 | ✓ | ✓ |
| 26 | S3 Container Failure & Restart Test (stop S3, PENDING, start S3, complete) | R3, R5, PLAN:237 | 5 | 5 | ✓ | ✓ |
| 27 | Memory Budget Verification (<85% container limit, heap <512MB) | R5, PLAN:242 | 5 | 5 | ✓ | ✓ |
| 28 | Stage Finalization Reporting (`README.md`, `CHANGELOG-ETAPE.md`, Romanian report) | R5, AGENTS.md | 5 | 5 | ✓ | ✓ |
| **Total** | **All 28 Stage 4 Features** | | **140** | **140** | **20** | **8** |

**Grand Total Test Inventory**: **308 concrete test cases**.

---

## Test Architecture

### 1. Dual-Harness Execution Model
The E2E testing infrastructure is organized into two complementary harnesses:

1. **Layer 1: In-JVM Component Integration Harness (`*IT.java`)**:
   - Location: `backend/src/test/java/ro/filegrid/`
   - Runner: `.\mvnw.cmd verify` (via `maven-failsafe-plugin:3.5.6`)
   - Environment: Testcontainers connecting via Windows named pipe `\\.\pipe\docker_engine` (`npipe:////./pipe/docker_engine`).
   - Strict Memory Ceiling: `<test.jvm.args>-Xmx256m</test.jvm.args>` enforced on test JVM to guarantee non-buffering streaming.
   - Modules Covered: Tus chunking, multipart streaming, SHA-256 calculation, RabbitMQ queues & DLQs, worker streaming PUT, read-back verification, S3 multipart persistence, idempotency.

2. **Layer 2: System-Level Black-Box E2E Runner (`scripts/run-e2e.ps1` & `scripts/run-e2e.sh`)**:
   - Location: `scripts/run-e2e.ps1` (PowerShell for Windows) and `scripts/run-e2e.sh` (Bash)
   - Target: Live Docker Compose stack running with Gateway (`https://localhost`), API, workers, PostgreSQL, RabbitMQ, S3/MinIO, FTP.
   - Resource Monitoring: Integrates `scripts/mem-report.sh` / `scripts/mem-report.ps1` to sample container memory usage in real time.
   - Scenarios: Live Gateway SSL/TLS routing, SSE event stream consumption, 1.5GB interrupted tus upload & clean resume, S3 container kill/restart resilience, horizontal worker scaling (`--scale worker-video=3`).

### 2. Directory & Component Layout
```
backend/src/test/java/ro/filegrid/
├── testsupport/
│   ├── DeterministicContent.java       # Zero-heap streaming byte generator with SHA-256
│   ├── TestImages.java                 # Centralized Docker image coordinates (Postgres, RabbitMQ, S3, FTP)
│   ├── TusTestClient.java              # Standalone Tus 1.0 client (POST/PATCH/HEAD/DELETE)
│   └── SseTestReceiver.java            # Non-blocking SSE consumer & event accumulator
├── upload/
│   ├── TusUploadIT.java                # Resumable tus 1.0 upload tests (Tiers 1 & 2)
│   └── MultipartUploadIT.java          # Small file direct multipart upload tests (Tiers 1 & 2)
├── ingestion/
│   └── IngestionAndQueuingIT.java      # Plaintext SHA-256, classification, routing, RabbitMQ queuing
├── worker/
│   ├── WorkerTransferIT.java           # Streaming PUT, read-back hash check, fallback, PENDING
│   ├── S3MultipartResumptionIT.java    # S3 multipart DB persistence & crash resumption
│   └── JobIdempotencyIT.java           # Duplicate delivery acknowledgement without re-writing
└── e2e/
    ├── FullPipelineE2EIT.java          # In-JVM end-to-end multi-tier integration test
    └── EventStreamingE2EIT.java        # Live SSE streaming & Agent 01 Romanian message verification

scripts/
├── run-e2e.ps1                         # PowerShell master E2E test runner
├── run-e2e.sh                          # Bash master E2E test runner
├── mem-report.ps1                      # Native PowerShell container memory monitor
└── mem-report.sh                       # Container memory and OOM-kill verification
```

### 3. Protocol & Assertion Contracts

#### Agent 01 Romanian Messages Assertion Contract
All SSE events emitted to `filegrid.events` and received at `GET /api/events/stream` must match the canonical Romanian string templates:
```
^Am primit un fișier nou\.$|^Analizez conținutul…$|^Am identificat categoria: .+\.$|^Am selectat (S3|FTP|Database) pentru acest fișier\.$|^Transfer în desfășurare…$|^Transferul a fost întrerupt\. Îl voi relua\.$|^Fișier stocat și verificat\.$|^Neconcordanță de integritate la verificare.*$|^Storage-ul principal nu a putut fi folosit.*$|^Nu există momentan o destinație disponibilă\. Fișierul a rămas în așteptare\.$|^Transferul a eșuat.*$
```

#### SSE Payload Schema Contract
Every event must contain:
- `fileId`: valid UUID v4
- `category`: `METER_READING` | `FAULT_RECORD` | `CONNECTION_DOCUMENT` | `DRONE_VIDEO`
- `status`: `RECEIVED` | `ANALYZING` | `CATEGORIZED` | `DESTINATION_SELECTED` | `TRANSFERRING` | `TRANSFER_RESUMED` | `STORED_VERIFIED` | `MISMATCH` | `FALLBACK` | `PENDING` | `TRANSFER_FAILED`
- `storage`: `S3` | `FTP` | `DATABASE` (nullable during initial analysis)
- `message`: Romanian text matching §8.1
- `bytesTransferred`: Long `>= 0` and `<= totalBytes`
- `totalBytes`: Long `>= 0`
- `timestamp`: ISO-8601 UTC string
- `traceId`: Non-empty string

---

## Real-World Industrial Grid Application Scenarios (Tier 4)

| # | Scenario | Features Exercised | Complexity | Description |
|---|----------|--------------------|------------|-------------|
| 1 | Meter Storm Telemetry Burst | F6, F9, F10, F11, F13, F14, F15, F21 | High | Rapid burst of 50 smart meter telemetry files via multipart `POST /api/files`. Verifies concurrent streaming to staging, queue routing to `filegrid.jobs.meter`, worker database storage, read-back hash verification, and zero dropped jobs. |
| 2 | 4K Substation Drone Inspection Pipeline | F1, F2, F3, F4, F10, F11, F14, F15, F20, F25, F27 | Very High | 1.5GB drone video upload via tus 1.0 interrupted at 40%, verified via HEAD offset, resumed to completion. Worker transfers to S3 via multipart upload while memory stays strictly below 85% limit and heap <512MB. |
| 3 | Transmission Fault Handling with Storage Fallback | F6, F9, F11, F14, F17, F18, F22, F23 | High | High-priority COMTRADE fault record submitted during FTP primary storage outage. System retries with exponential backoff + jitter, triggers fallback to S3, emits Romanian `FALLBACK` event, and stores verified file. |
| 4 | Grid Black-Sky Resilience: S3 Outage & Crash Resumption | F1, F11, F14, F19, F20, F26 | Very High | Mid-transfer S3 container killed (`docker compose stop s3`). Worker parks file in `PENDING` with staging intact. Upon S3 restart (`docker compose start s3`), scheduled recovery detects `UP` health, resumes multipart upload from last confirmed part (`TRANSFER_RESUMED`), and reaches `STORED_VERIFIED`. |
| 5 | Multi-Category Bulkhead Concurrency Under Load | F11, F13, F14, F24 | High | Video queue saturated with multiple large transfers while high-priority meter and fault jobs arrive. Verifies `worker-meter` and `worker-fault` process immediately without being blocked by video backlog. |
| 6 | Total Storage Blackout & Extended Staging Retention | F6, F18, F19, F22, F23 | High | Complete storage blackout (all storages down). Files safely held in `/data/staging` in `PENDING` state. Upon storage restoration, all queued files are sequentially processed and verified without data loss. |
| 7 | Transmission Line COMTRADE Corruption & Incident Alert | F6, F10, F15, F16, F22, F23 | Medium | Malformed/corrupted file injected during read-back. Verifies worker detects hash mismatch, sets `MISMATCH`, deletes corrupted target object, retries transfer, and logs integrity incident. |
| 8 | 24-Hour Operational Cycle Simulation | F1, F5, F6, F9, F11, F15, F21, F22 | High | Continuous 24h operational cycle simulation: interleaved tus uploads, multipart uploads, duplicate job deliveries, abandoned upload purging (>24h), and live SSE event streaming. |

---

## Coverage Thresholds & Pass Criteria

- **Tier 1 (Feature Coverage)**: Exactly 140 test cases (5 per feature for all 28 features). 100% pass rate.
- **Tier 2 (Boundary & Corner Cases)**: Exactly 140 test cases (5 per feature for all 28 features). 100% pass rate.
- **Tier 3 (Cross-Feature Interactions)**: Exactly 20 pairwise/cross-component test cases. 100% pass rate.
- **Tier 4 (Real-World Application Scenarios)**: Exactly 8 end-to-end industrial grid scenarios. 100% pass rate.
- **Memory Ceiling**:
  - Test JVM: `<test.jvm.args>-Xmx256m</test.jvm.args>` without `OutOfMemoryError`.
  - Docker Compose: `worker-video` peak memory < 85% of 512MB limit (435MB); `api` < 85% of 640MB limit (544MB); zero containers OOMKilled.
