# RWC Waste Tracking System

Implemented workflows are **normal Crate / Pallet** and **Salvage**: sign in, enter an operational record, review calculated weights, submit, and view saved records. Admins can correct and soft-delete submitted records. Other workflows, reporting, dashboards, and user management are not implemented.

Requirements: [AGENTS.md](AGENTS.md) and the approved [SRS](docs/SRS-%20Waste%20Tracking%20Operations%20System%20for%20Resource%20Work%20Cooperative.pdf), supplemented by the approved Crate/Pallet implementation decisions.

## Local setup

Use JDK 21 or later, Maven 3.9+, Node.js compatible with the existing Vite installation, and PostgreSQL. The commands below use Windows PowerShell and installed Maven. Use `npm.cmd` to avoid PowerShell script execution-policy restrictions.

Create/provide a PostgreSQL database and application role before starting. Defaults are `jdbc:postgresql://localhost:5433/rwc_waste_tracking` and username `rwc_app`; override through `DB_URL` and `DB_USERNAME` when necessary. The role must be able to apply Flyway migrations to its schema.

Set these environment variables in the backend terminal:

| Variable | Purpose |
| --- | --- |
| `DB_PASSWORD` | Required database password, with no source-code default |
| `DB_URL` | Optional JDBC URL override |
| `DB_USERNAME` | Optional database username override |
| `INITIAL_STAFF_USERNAME`, `INITIAL_STAFF_PASSWORD` | Shared Staff account for initial provisioning |
| `INITIAL_ADMIN_USERNAME`, `INITIAL_ADMIN_PASSWORD` | Individual initial Admin account for initial provisioning |
| `SESSION_COOKIE_SECURE` | Set `true` when serving through HTTPS; defaults to `false` for local HTTP |

For example, enter credentials without including passwords in command history:

```powershell
$env:DB_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host 'Database password' -AsSecureString)).Password
$env:INITIAL_STAFF_USERNAME = Read-Host 'Shared Staff username'
$env:INITIAL_STAFF_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host 'Initial Staff password' -AsSecureString)).Password
$env:INITIAL_ADMIN_USERNAME = Read-Host 'Initial Admin username'
$env:INITIAL_ADMIN_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host 'Initial Admin password' -AsSecureString)).Password
Set-Location backend
mvn.cmd spring-boot:run
```

Provisioning hashes passwords with BCrypt. It creates missing accounts and never resets existing passwords. Supply both username and password for a role, or omit both. A conflicting role or second shared Staff account causes startup to fail. Remove initial-account password variables from later launches after provisioning. Do not put credentials or hashes into migrations or tracked files. `.env` files are ignored by Git but are not loaded automatically by Spring Boot.

In a second terminal:

```powershell
Set-Location frontend
npm.cmd ci
npm.cmd run dev
```

Open the Vite address printed in the terminal. Vite proxies `/api` to the backend on port 8080. `API_PROXY_TARGET` can override that target. Authentication uses an HttpOnly session cookie and session-backed CSRF tokens; no JWT or browser token storage is used.

For deployment, serve the frontend and `/api` from the same origin through HTTPS, proxying API requests to Spring Boot. Set `SESSION_COOKIE_SECURE=true`. The Vite development server is not the production hosting setup.

## Using the workflow

1. Sign in with the shared Staff account or your Admin account.
2. Choose **New Crate / Pallet**. Enter the record date, container, gross weight including the container, and material percentages.
3. Percentages must total exactly **100.00%**. Select **Review record** to calculate the tare, net weight, and material weights on the backend.
4. Use **Back to edit** to correct inputs, then review again. Nothing is stored until **Confirm and submit**.
5. Successful submission opens **Records** with the saved record. Filter by date and select a record number to see its breakdown.
6. Admins can choose **Correct record**, review the correction, and confirm. **Delete record** requires a confirmation and hides the record while retaining database traceability. Staff cannot edit or delete submitted records.

While submitting, navigation, logout, and starting another record are disabled. If a response is lost, use **Retry submission**: it reuses the same UUID and payload. You can also check Records and choose **Resume submission** to return to the unresolved request; its state stays in application memory. Another record cannot replace it while unresolved, and logout is disabled until the outcome is resolved. Reloading or closing the page prompts a browser warning but still discards state if you choose to leave. There are no persistent drafts or browser-storage copies.

A submission-ID/data conflict (HTTP 409) is a definite rejection, not a transport failure: open Records to inspect the existing record instead of repeatedly retrying. Admin corrections also return 409 when their version is stale; reload the record before making another correction. Session-expiration recovery remains outside this change.

If a delete response is lost, retry deletion or refresh Records. A not-found response reconciles the list and clears selected details. Refresh also replaces selected details with the current record or clears them if that record is no longer listed.

If the system is unavailable, use the existing paper forms. An authorized Admin can enter them later using the original operational dates.

## Salvage trips

Choose **New Salvage**. Enter one operational record date and one mandatory crew for the complete trip. Always enter Time Up, Kg Up, Time Down, and Kg Down. These are local operational times; Down may be earlier than or equal to Up. The date must be past or current in Australia/Hobart.

Enter the final Kg Up (waste to landfill) and Kg Down (recovered material) supplied by the council weighbridge. Both weights are recorded independently, with no tare deduction, truck-weight difference calculation, or subtraction of one direction from the other. No digital dockets are stored.

For each direction independently:

- A positive weight requires a material breakdown totaling exactly 100.00%.
- A zero weight still requires its time, hides the percentage inputs, and persists no material rows. Both weights may be zero.
- Changing a positive weight to zero clears its percentages. An Admin correction also removes the old persisted rows. Changing back to positive requires a valid 100% breakdown.
- Category weight is council weight multiplied by percentage divided by 100, rounded individually to two decimals with HALF_UP. Differences are not redistributed.

Select **Review trip**, check both directions and the single crew, then confirm. The same version checks, duplicate-submission protection, in-memory recovery, navigation guards, role permissions, and soft deletion apply. Records has a **Workflow** selector and inclusive From/To date filters. Record identities include the workflow, so equal numeric IDs in the two workflows remain separate.

Material definitions are shared through `material_category`; applicability is restricted by `CRATE_PALLET`, `SALVAGE_UP`, and `SALVAGE_DOWN`. Both the definition and its applicability must be active for a new selection. The backend checks applicability on writes, including direct API submissions. A historical inactive category can be retained during correction in its original direction.

| Salvage Up: waste to landfill (9) | Salvage Down: recovered material (14) |
| --- | --- |
| Iron and Steel; Timber; Textiles; Plastics; Paper, Cardboard and Books; Mattresses; E-waste; Glass; Mixed Materials | Iron and Steel; Aluminium; Other Non-Ferrous Metals; Mixed Metals; E-waste; Lead Acid Batteries; Timber; Bricks, Blocks and Pavers; Building and Demolition Material; Whitegoods / Large Appliances; Paper, Cardboard and Books; Plastics; Glass; Mixed Materials |

E-waste is intentionally available in both Salvage directions. E-waste, Lead Acid Batteries, and Whitegoods / Large Appliances remain unavailable in normal Crate/Pallet.

## Data and calculation rules

- Eight application tables: `app_user`, `container_type`, `material_category`, `material_category_applicability`, `crate_pallet_record`, `crate_pallet_material`, `salvage_record`, and `salvage_material`.
- Containers: Apple Crate 60 kg, Stillage Cage 50 kg, Plastic Pallet 6 kg, Pine Pallet 18 kg, Hardwood Pallet 30 kg.
- The thirteen normal material categories remain restricted to Crate/Pallet. Three additional shared definitions and direction-specific applicability mappings support Salvage.
- Weights use Java `BigDecimal` and PostgreSQL `NUMERIC(10,2)`; percentages use `NUMERIC(5,2)`. Inputs accept at most two decimal places.
- Net weight is gross minus tare; gross cannot be below tare. Zero net weight is allowed.
- Material weight is `net * entered percentage / 100`, rounded independently to two decimals with `HALF_UP`. Rounding differences are not redistributed.
- Dates are checked against the current date in `Australia/Hobart`; future dates are rejected.
- Only positive-percentage breakdown rows are saved. Duplicate categories are rejected.
- Final submission recalculates all values instead of trusting frontend-calculated fields. The record and breakdown are saved atomically.
- `submission_id` is a unique UUID. Matching retries by the same creator return the existing record; reuse with different data, by another account, or after deletion returns HTTP 409.
- Records carry a numeric JPA optimistic-locking `version`. Corrections must submit the version read from the saved record. Stale versions return HTTP 409 without overwriting newer changes; missing correction versions return HTTP 400.
- Creation and latest-update user/timestamps are stored on the record. Deletion sets `deleted_at` and `deleted_by`; ordinary reads exclude deleted records. Intermediate edit history and JSON snapshots are not stored.
- Reference rows have `active` flags and are not physically removed while referenced. New entries use active references. Existing records retain their stored tare; an Admin correction keeps that tare when the container is unchanged and may retain previously used inactive references.

## API

All operational endpoints require a session. Mutating requests also require the CSRF header obtained from `GET /api/auth/csrf`. Decimal response fields are strings to preserve precision in TypeScript.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET | `/api/auth/csrf` | Obtain header name and CSRF token, including before login |
| POST | `/api/auth/login` | Form-encoded username/password; 204 on success |
| POST | `/api/auth/logout` | End the session |
| GET | `/api/auth/me` | Account role and current Hobart operational date |
| GET | `/api/container-types` | Active container types and tare values |
| GET | `/api/material-categories?context=CRATE_PALLET` | Active applicable categories; context defaults to Crate/Pallet, or accepts SALVAGE_UP / SALVAGE_DOWN |
| POST | `/api/crate-pallet-records/preview` | Validate and calculate without saving |
| POST | `/api/crate-pallet-records` | Submit; 201 for creation, 200 for a matching retry |
| GET | `/api/crate-pallet-records?from=YYYY-MM-DD&to=YYYY-MM-DD` | List non-deleted records; optional inclusive date filters |
| GET | `/api/crate-pallet-records/{id}` | Read one non-deleted record |
| PUT | `/api/crate-pallet-records/{id}` | Admin correction |
| DELETE | `/api/crate-pallet-records/{id}` | Admin soft deletion |

Preview accepts optional `?recordId={id}` for an Admin correction, preserving historical tare/reference behavior. The body for preview/submission/correction has `recordDate`, `containerTypeId`, `grossWeight`, and `materials` (`materialCategoryId`, `percentage`). Final POST additionally requires `submissionId`. Admin edit-preview and PUT correction additionally require `version` from a saved-record response. No record status or draft endpoints exist.

Salvage uses the same methods at `/api/salvage-records`: POST `/preview`, final POST, list GET with optional `from`/`to`, detail GET `/{id}`, Admin PUT `/{id}`, and Admin DELETE `/{id}`. Correction preview accepts `?recordId={id}` and requires `version`.

Salvage input contains `recordDate`, `crew`, and separate `up` / `down` objects, each with `time`, `kg`, and optional `materials` (`materialCategoryId`, `percentage`). Final POST requires `submissionId`; corrections require `version`. A zero direction can omit materials or send an empty array. Stale submitted percentages for a zero direction are discarded rather than persisted. Both `time` values and both weights remain mandatory. Responses add IDs/version, category names/calculated material weights, and account/timestamp metadata; decimals are strings.

Duplicate comparison includes the normalized crew, date, both times, both weights, and each direction's normalized positive-percentage rows. Raw calculated weights supplied by clients are ignored; final submission recalculates the accepted values. Both breakdowns and the parent are saved within one transaction.

## Structure

Backend packages under `au.org.resourcework.waste_tracking_api`: `auth`, `config`, `reference`, `cratepallet`, `salvage`, and `error`. Workflow controllers call services, which own transactions and call repositories, and use DTOs. Flyway SQL lives in `backend/src/main/resources/db/migration`; Hibernate validates rather than creates tables. V1/V2 are preserved, V3 adds the initial schema, V4 seeds reference data, V5 adds Crate/Pallet versioning, V6 adds category applicability, and V7 adds Salvage.

Frontend: `api` for HTTP/CSRF handling; `features/auth`, `features/crate-pallet`, and `features/salvage`; workflow-qualified record types in `features/records`; shared decimal/date helpers in `utils`; `pages/RecordsPage.tsx`; and `styles`. Components use local React state and native fetch; no Redux or additional application framework is introduced.

## Verification

Run the backend calculation and isolation-guard unit tests without a database:

```powershell
Set-Location backend
mvn.cmd verify
```

The PostgreSQL integration tests are explicitly gated behind `RUN_DB_TESTS=true` and require runner-supplied `RWC_TEST_DB_*` settings for a verified temporary `rwc_slice_test` database. They delete test records; use the isolated runner from the repository root:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-backend.ps1
```

The runner starts a loopback-only PostgreSQL instance on a free port, generates temporary credentials, and marks the database with a unique per-run identity token. It removes inherited `SPRING_*` settings and JVM/Maven property injection, loads only the classpath application configuration, and explicitly pins datasource, Hikari, and Flyway URLs/credentials/schema to the temporary database. Original environment values are restored afterward.

Before Spring bean creation or Flyway migration, a test initializer verifies the database URL, name, account, address, port, and identity token and pins the connection properties again. Integration setup rechecks the actual datasource before modifying data. Teardown only runs after successful verification and rechecks isolation before its updates. Unit tests cover redirected connections, wrong identity tokens, property overrides, and teardown after failed setup.

The runner executes 34 tests (14 unit tests and 20 integration tests), packages the backend, and stops PostgreSQL. Salvage tests cover all four zero/positive combinations, mandatory times, independent totals/rounding, correction transitions, exact category sets and API restrictions, historical applicability, concurrency, metadata/permissions, soft deletion, and rollback after an injected material-write failure. Supply `-PostgresBin` to override the default PostgreSQL 18 binary directory. Generated test clusters/logs remain under ignored `backend/target`.

For the complete browser smoke test:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-backend.ps1 -Browser
```

This also builds/lints the frontend and launches temporary backend, Vite preview, and headless Chrome processes. It tests session/CSRF login, percentage validation, review, navigation/logout guards during submission, retaining the retry UUID across a Records visit, definitive conflict handling, Staff read-only access, Admin correction, lost-delete-response/404 reconciliation, selected-detail refresh, and tablet layout. It uses the existing Node/Chrome DevTools smoke runner without adding a framework. Set `CHROME_PATH` if Chrome/Edge is installed elsewhere. Screenshots/logs are under ignored `backend/target/browser-test-*`. Test processes are stopped afterward.

The browser suite also exercises Salvage's distinct category sets, one crew, earlier Down times, all four zero/positive combinations with mandatory times, hidden zero-direction inputs, positive-to-zero and zero-to-positive corrections, stale-version recovery, and retry/navigation across workflow views.

Frontend checks separately:

```powershell
Set-Location frontend
npm.cmd run build
npm.cmd run lint
```
