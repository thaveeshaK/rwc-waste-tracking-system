# RWC Waste Tracking System

The first implemented workflow is **normal Crate / Pallet**: sign in, enter a load, review calculated weights, submit, and view saved records. Admins can correct and soft-delete submitted records. Other workflows, reporting, dashboards, and user management are not implemented.

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

If a submission response is lost, use **Retry submission**. It reuses the same UUID and payload; the database returns the original record rather than inserting a duplicate. Check Records before starting another entry if the outcome is uncertain. Unsaved form data and retry state are held in page memory and are lost on reload or navigation; there are no persistent drafts.

If the system is unavailable, use the existing paper forms. An authorized Admin can enter them later using the original operational dates.

## Data and calculation rules

- Five application tables: `app_user`, `container_type`, `material_category`, `crate_pallet_record`, and `crate_pallet_material`.
- Containers: Apple Crate 60 kg, Stillage Cage 50 kg, Plastic Pallet 6 kg, Pine Pallet 18 kg, Hardwood Pallet 30 kg.
- Thirteen normal material categories are seeded. E-waste and electrical-only categories are excluded.
- Weights use Java `BigDecimal` and PostgreSQL `NUMERIC(10,2)`; percentages use `NUMERIC(5,2)`. Inputs accept at most two decimal places.
- Net weight is gross minus tare; gross cannot be below tare. Zero net weight is allowed.
- Material weight is `net * entered percentage / 100`, rounded independently to two decimals with `HALF_UP`. Rounding differences are not redistributed.
- Dates are checked against the current date in `Australia/Hobart`; future dates are rejected.
- Only positive-percentage breakdown rows are saved. Duplicate categories are rejected.
- Final submission recalculates all values instead of trusting frontend-calculated fields. The record and breakdown are saved atomically.
- `submission_id` is a unique UUID. Matching retries by the same creator return the existing record; reuse with different data, by another account, or after deletion returns HTTP 409.
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
| GET | `/api/material-categories` | Active normal material categories |
| POST | `/api/crate-pallet-records/preview` | Validate and calculate without saving |
| POST | `/api/crate-pallet-records` | Submit; 201 for creation, 200 for a matching retry |
| GET | `/api/crate-pallet-records?from=YYYY-MM-DD&to=YYYY-MM-DD` | List non-deleted records; optional inclusive date filters |
| GET | `/api/crate-pallet-records/{id}` | Read one non-deleted record |
| PUT | `/api/crate-pallet-records/{id}` | Admin correction |
| DELETE | `/api/crate-pallet-records/{id}` | Admin soft deletion |

Preview accepts optional `?recordId={id}` for an Admin correction, preserving historical tare/reference behavior. The body for preview/submission/correction has `recordDate`, `containerTypeId`, `grossWeight`, and `materials` (`materialCategoryId`, `percentage`). Final POST additionally requires `submissionId`. No record status or draft endpoints exist.

## Structure

Backend packages under `au.org.resourcework.waste_tracking_api`: `auth`, `config`, `reference`, `cratepallet` (with `dto`), and `error`. Workflow controllers call services, which own transactions and call repositories. Flyway SQL lives in `backend/src/main/resources/db/migration`; Hibernate validates rather than creates tables. V1/V2 are preserved, V3 adds the schema, and V4 seeds reference data.

Frontend: `api` for HTTP/CSRF handling, `features/auth`, `features/crate-pallet`, `pages/RecordsPage.tsx`, and `styles`. Components use local React state and native fetch; no Redux or additional application framework is introduced.

## Verification

Run the default backend calculation tests without a database:

```powershell
Set-Location backend
mvn.cmd verify
```

The PostgreSQL integration tests are explicitly gated behind `RUN_DB_TESTS=true` and require the dedicated `rwc_slice_test` database. They delete test records; **do not point them at an operational database**. Use the isolated runner from the repository root:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-backend.ps1
```

The runner starts a separate loopback-only PostgreSQL instance on a free port, generates temporary database/account credentials, runs all ten tests and packages the backend, then stops PostgreSQL and restores environment variables. It never connects to your configured operational database. Supply `-PostgresBin` to override the default PostgreSQL 18 binary directory. Generated test clusters/logs remain under ignored `backend/target`.

For the complete browser smoke test:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-backend.ps1 -Browser
```

This also builds/lints the frontend and launches temporary backend, Vite preview, and headless Chrome processes. It tests actual session/CSRF login, percentage validation, review, a lost POST response and retry with the same UUID, saved Records, Staff read-only access, Admin correction/deletion, and tablet layout. It uses Node APIs and Chrome DevTools without adding a test framework. Set `CHROME_PATH` if Chrome/Edge is installed elsewhere. Screenshots/logs are under ignored `backend/target/browser-test-*`. Test processes are stopped afterward.

Frontend checks separately:

```powershell
Set-Location frontend
npm.cmd run build
npm.cmd run lint
```
